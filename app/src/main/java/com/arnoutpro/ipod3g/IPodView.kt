package com.arnoutpro.ipod3g

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Draws the whole iPod and turns touches on the wheel and buttons into iPod input. */
@SuppressLint("ViewConstructor")
class IPodView(private val host: MainActivity) : View(host), SensorEventListener {
    val lcd = Lcd(host)

    private val handler = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lcdPaint = Paint() // no filtering: crisp pixels
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lcdRect = Rect()
    private val body = RectF()
    private val bezel = RectF()
    private val btnX = FloatArray(4)
    private var btnY = 0f
    private var btnR = 0f
    private var wheelX = 0f
    private var wheelY = 0f
    private var wheelR = 0f
    private var centerR = 0f
    private var bw = 0f
    private var bodyShader: Shader? = null
    private var wheelShader: Shader? = null
    private var centerShader: Shader? = null
    private var btnShader: Shader? = null
    private var glassShader: Shader? = null
    private var glossShader: Shader? = null
    private var lcdShadow: Shader? = null
    // Reused while drawing, so a frame creates no garbage.
    private val rim = RectF()
    private val glyphPath = Path()
    // Holes in the acrylic top layer that the buttons and the wheel sit sunk in.
    private val btnWall = arrayOfNulls<Shader>(4)
    private val btnEdge = arrayOfNulls<Shader>(4)
    private var wheelWall: Shader? = null
    private var wheelEdge: Shader? = null
    private var centerWall: Shader? = null
    private var centerEdge: Shader? = null
    private var btnHole = 0f
    private var wheelHole = 0f
    private var centerHole = 0f

    // The buttons and the wheel, recorded once and kept on the GPU while only the LCD changes:
    // recorded again when a press, the backlight or the light changes. Android 10 and later.
    private val controls = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) RenderNode("controls") else null
    private var drawnSize = 0L
    private var lightVersion = 0
    private var drawnLight = -1
    private var drawnPressed = T_NONE
    private var drawnLit = false

    // Light from the room's ceiling, followed with the gravity sensor: the direction on screen
    // towards it, and how far the phone is tilted up from lying flat (0..1).
    private var gravX = 0f
    private var gravY = SensorManager.GRAVITY_EARTH * 0.8f
    private var lightX = 0f
    private var lightY = -1f
    private var lightTilt = 0.8f
    private val glossMatrix = Matrix()

    private var finish = Finish.WHITE

    // touch state
    private var target = T_NONE
    private var pressed = T_NONE
    private var lastAngle = 0f
    private var accum = 0f
    private var downX = 0f
    private var downY = 0f
    private var holdFired = false
    private var seekDir = 0

    // backlight
    private var lit = true
    private var litAtDown = true
    // Turned off from the Backlight menu: stays dark until turned on there again.
    private var forcedOff = false
    private var lastActivity = 0L

    private var tiltWanted = false
    private var tiltOn = false

    private val holdRunnable = Runnable { onHold() }
    private val seekRunnable = object : Runnable {
        override fun run() {
            val ps = host.playback()
            if (seekDir == 0 || ps == null) return
            ps.seekTo(ps.getPosition() + seekDir * 5000)
            handler.postDelayed(this, SEEK_REPEAT_MS)
        }
    }
    private val backlightCheck = Runnable { checkBacklight() }
    private val frame = Runnable { invalidate() }

    init {
        lcdPaint.isFilterBitmap = false
        lcdPaint.isAntiAlias = false
        glyph.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        glyph.textAlign = Paint.Align.CENTER
        isHapticFeedbackEnabled = true
        wake()
    }

    // ---- layout ----------------------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        // The face fills the whole screen. Everything scales with one unit, taken from the
        // width unless the screen is too short for the stack, and spare height is shared
        // out between the sections.
        body.set(0f, 0f, w.toFloat(), h.toFloat())
        bw = minOf(w.toFloat(), h / CONTENT_H)
        val spare = h - bw * CONTENT_H
        val cx = w / 2f

        val lw = bw * LCD_W
        val lh = lw * Lcd.H / Lcd.W
        val pad = bw * BEZEL
        val bezelTop = bw * 0.07f + spare * 0.30f
        val lx = cx - lw / 2f
        val ly = bezelTop + pad
        lcdRect.set(lx.roundToInt(), ly.roundToInt(), (lx + lw).roundToInt(), (ly + lh).roundToInt())
        bezel.set(lcdRect.left - pad, lcdRect.top - pad, lcdRect.right + pad, lcdRect.bottom + pad)

        btnR = bw * 0.09f
        btnY = bezel.bottom + bw * 0.06f + spare * 0.20f + btnR
        val spacing = bw * 0.235f
        for (i in 0 until 4) btnX[i] = cx + (i - 1.5f) * spacing

        wheelR = bw * 0.36f
        wheelX = cx
        wheelY = btnY + btnR + bw * 0.06f + spare * 0.15f + wheelR
        centerR = wheelR * 0.35f

        btnHole = btnR * 1.1f
        wheelHole = wheelR + bw * 0.014f
        centerHole = centerR + bw * 0.006f
        buildShaders()
    }

    fun setFinish(f: Finish) {
        if (f === finish) return
        finish = f
        buildShaders()
        invalidate()
    }

    private fun buildShaders() {
        if (bw == 0f) return
        val f = finish
        wheelShader = RadialGradient(wheelX, wheelY - wheelR * 0.3f, wheelR * 1.3f, f.wheelHi, f.wheelLo, Shader.TileMode.CLAMP)
        centerShader = LinearGradient(0f, wheelY - centerR, 0f, wheelY + centerR, f.centerTop, f.centerBottom, Shader.TileMode.CLAMP)
        btnShader = LinearGradient(0f, btnY - btnR, 0f, btnY + btnR, f.btnTop, f.btnBottom, Shader.TileMode.CLAMP)
        lcdShadow = LinearGradient(
            0f, lcdRect.top.toFloat(), 0f, lcdRect.top + bw * 0.03f,
            0x50000000, 0x00000000, Shader.TileMode.CLAMP,
        )
        buildHoles()
    }

    private fun buildHoles() {
        if (bw == 0f) return
        lightVersion++
        for (i in 0 until 4) {
            btnWall[i] = wallShader(btnX[i], btnY, btnHole, 0x20)
            btnEdge[i] = edgeShader(btnX[i], btnY, btnHole)
        }
        wheelWall = wallShader(wheelX, wheelY, wheelHole, 0x1C)
        wheelEdge = edgeShader(wheelX, wheelY, wheelHole)
        centerWall = wallShader(wheelX, wheelY, centerHole, 0x14)
        centerEdge = edgeShader(wheelX, wheelY, centerHole)
        buildLight()
    }

    /**
     * The body's shading, the gloss on the acrylic and the reflection on the screen glass, all
     * brightest on the side facing the light, and fainter the flatter the phone lies.
     */
    private fun buildLight() {
        val f = finish
        val k = 0.35f + 0.65f * lightTilt
        val cx = body.centerX()
        val cy = body.centerY()
        val half = hypot(body.width(), body.height()) / 2f
        val sx = cx + lightX * half // the edge nearest the light
        val sy = cy + lightY * half

        bodyShader = LinearGradient(
            sx, sy, cx - lightX * half, cy - lightY * half,
            intArrayOf(blend(f.body[1], f.body[0], k), f.body[1], blend(f.body[1], f.body[2], k)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )

        // A broad sheen across the acrylic from the light's side, sliding the other way as the
        // phone tilts, like a reflection.
        val len = hypot(body.width(), body.height() * 0.55f)
        val gloss = IntArray(f.gloss.size) { fade(f.gloss[it], k) }
        glossShader = LinearGradient(
            sx, sy, sx - lightX * len, sy - lightY * len,
            gloss, floatArrayOf(0f, 0.3f, 0.48f, 0.56f, 0.7f), Shader.TileMode.CLAMP,
        ).apply {
            glossMatrix.setTranslate(-lightX * lightTilt * bw * 0.12f, -lightY * lightTilt * bw * 0.12f)
            setLocalMatrix(glossMatrix)
        }

        val bh = hypot(bezel.width(), bezel.height()) / 2f
        val gx = bezel.centerX() + lightX * bh
        val gy = bezel.centerY() + lightY * bh
        val glen = hypot(bezel.width() * 0.6f, bezel.height())
        glassShader = LinearGradient(
            gx, gy, gx - lightX * glen, gy - lightY * glen,
            fade(0x30FFFFFF, k), 0x00FFFFFF, Shader.TileMode.CLAMP,
        )
    }

    /** Shadow the hole's wall casts inwards, deepest on the side the light comes from. */
    private fun wallShader(x: Float, y: Float, hole: Float, alpha: Int): Shader {
        val a = minOf(255, (alpha * finish.wallWeight).roundToInt())
        val off = hole * 0.14f * lightTilt
        val rad = hole + off
        return RadialGradient(
            x - lightX * off, y - lightY * off, rad,
            intArrayOf(0, 0, a shl 24),
            floatArrayOf(0f, hole * 0.8f / rad, 1f), Shader.TileMode.CLAMP,
        )
    }

    /** The cut edge of the acrylic: dark towards the light, catching it on the far side. */
    private fun edgeShader(x: Float, y: Float, hole: Float): Shader {
        val k = 0.35f + 0.65f * lightTilt
        val dx = lightX * hole
        val dy = lightY * hole
        return LinearGradient(
            x + dx, y + dy, x - dx, y - dy,
            intArrayOf((finish.edgeDark * k).toInt() shl 24, 0x06000000, (finish.edgeLight * k).toInt() shl 24 or 0xFFFFFF),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
        )
    }

    // ---- tilt ------------------------------------------------------------------------------

    /** The app is visible: follow the tilt, as long as the backlight is on. */
    fun startTilt() {
        tiltWanted = true
        updateTilt()
    }

    fun stopTilt() {
        tiltWanted = false
        updateTilt()
    }

    /** Listens to the gravity sensor only while it shows: visible and lit (saves battery). */
    private fun updateTilt() {
        val want = tiltWanted && lit
        if (want == tiltOn) return
        tiltOn = want
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        if (!want) {
            sm.unregisterListener(this)
            return
        }
        val g = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (g != null) sm.registerListener(this, g, SensorManager.SENSOR_DELAY_UI)
    }

    override fun onSensorChanged(e: SensorEvent) {
        // Smooth it, then only redraw for a visible change.
        gravX += (e.values[0] - gravX) * 0.2f
        gravY += (e.values[1] - gravY) * 0.2f
        val len = hypot(gravX, gravY)
        val tilt = minOf(1f, len / SensorManager.GRAVITY_EARTH)
        // The sensor's y points to the top of the screen, the canvas's y down.
        val nx = if (len > 0.01f) gravX / len else 0f
        val ny = if (len > 0.01f) -gravY / len else -1f
        if (abs(tilt - lightTilt) < 0.02f && hypot(nx - lightX, ny - lightY) < 0.03f) return
        lightX = nx
        lightY = ny
        lightTilt = tilt
        buildHoles()
        invalidate()
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    // ---- drawing ---------------------------------------------------------------------------

    override fun onDraw(c: Canvas) {
        val now = System.currentTimeMillis()
        val delay = lcd.render(lit, now)
        drawBody(c)
        drawScreen(c)
        drawControls(c)
        shade(glossShader)
        c.drawRect(body, paint)
        paint.shader = null

        if (delay >= 0) {
            handler.removeCallbacks(frame)
            // In the dark nothing on the LCD needs to move smoothly.
            handler.postDelayed(frame, if (lit) maxOf(16, delay) else maxOf(1000, delay))
        }
    }

    /** The buttons and the wheel, from the GPU's copy when nothing about them changed. */
    private fun drawControls(c: Canvas) {
        val node = controls
        if (node == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !c.isHardwareAccelerated) {
            for (i in 0 until 4) drawButton(c, i)
            drawWheel(c)
            return
        }
        val size = width.toLong() shl 32 or height.toLong()
        if (drawnLight != lightVersion || drawnPressed != pressed || drawnLit != lit || drawnSize != size) {
            drawnLight = lightVersion
            drawnPressed = pressed
            drawnLit = lit
            drawnSize = size
            node.setPosition(0, 0, width, height)
            node.setUseCompositingLayer(true, null)
            val rc = node.beginRecording()
            for (i in 0 until 4) drawButton(rc, i)
            drawWheel(rc)
            node.endRecording()
        }
        c.drawRenderNode(node)
    }

    /**
     * Selects a gradient at full opacity: a shader takes the paint's alpha, so a faint colour
     * left over from e.g. a pressed button would otherwise make the next gradient see-through.
     */
    private fun shade(s: Shader?) {
        paint.color = 0xFF000000.toInt()
        paint.shader = s
    }

    private fun drawBody(c: Canvas) {
        shade(bodyShader)
        c.drawRect(body, paint)
        paint.shader = null
    }

    private fun drawScreen(c: Canvas) {
        val r = bw * 0.024f
        rim.set(bezel)
        rim.inset(-bw * 0.008f, -bw * 0.008f)
        paint.color = finish.bezelRim
        c.drawRoundRect(rim, r * 1.3f, r * 1.3f, paint)
        paint.color = finish.bezel
        c.drawRoundRect(bezel, r, r, paint)

        lcdPaint.isFilterBitmap = lcd.modern // the 6th gen look is drawn smooth and larger
        c.drawBitmap(lcd.bitmap, null, lcdRect, lcdPaint)

        // Inner shadow along the top of the LCD, then the glass reflection.
        shade(lcdShadow)
        c.drawRect(lcdRect, paint)
        shade(glassShader)
        c.drawRoundRect(bezel, r, r, paint)
        paint.shader = null
    }

    private fun drawButton(c: Canvas, i: Int) {
        val x = btnX[i]
        paint.color = finish.floor // floor of the hole, around the button
        c.drawCircle(x, btnY, btnHole, paint)
        shade(btnShader)
        c.drawCircle(x, btnY, btnR, paint)
        paint.shader = null
        if (pressed == i) {
            paint.color = finish.pressBtn
            c.drawCircle(x, btnY, btnR, paint)
        }

        glyph.color = if (lit) GLOW else finish.glyphOff
        if (lit) glyph.setShadowLayer(btnR * 0.28f, 0f, 0f, 0xFFFF3A10.toInt()) else glyph.clearShadowLayer()
        val s = btnR * 0.26f
        val p = glyphPath
        p.reset()
        when (i) {
            B_PREV -> {
                c.drawRect(x - s * 1.6f, btnY - s, x - s * 1.2f, btnY + s, glyph)
                tri(p, x - s * 1.2f, btnY, s, -1)
                tri(p, x + s * 0.2f, btnY, s, -1)
                c.drawPath(p, glyph)
            }
            B_NEXT -> {
                tri(p, x - s * 1.6f, btnY, s, 1)
                tri(p, x - s * 0.2f, btnY, s, 1)
                c.drawPath(p, glyph)
                c.drawRect(x + s * 1.2f, btnY - s, x + s * 1.6f, btnY + s, glyph)
            }
            B_PLAY -> {
                tri(p, x - s * 1.5f, btnY, s, 1)
                c.drawPath(p, glyph)
                c.drawRect(x + s * 0.25f, btnY - s, x + s * 0.7f, btnY + s, glyph)
                c.drawRect(x + s * 1.05f, btnY - s, x + s * 1.5f, btnY + s, glyph)
            }
            B_MENU -> {
                glyph.textSize = btnR * 0.42f
                val fm = glyph.fontMetrics
                c.drawText("MENU", x, btnY - (fm.ascent + fm.descent) / 2f, glyph)
            }
        }
        drawHole(c, x, btnY, btnHole, btnWall[i], btnEdge[i])
    }

    private fun drawHole(c: Canvas, x: Float, y: Float, hole: Float, wall: Shader?, edge: Shader?) {
        shade(wall)
        c.drawCircle(x, y, hole, paint)
        shade(edge)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = bw * 0.006f
        c.drawCircle(x, y, hole, paint)
        paint.style = Paint.Style.FILL
        paint.shader = null
    }

    private fun drawWheel(c: Canvas) {
        paint.color = finish.floor // floor of the hole, around the wheel
        c.drawCircle(wheelX, wheelY, wheelHole, paint)
        shade(wheelShader)
        c.drawCircle(wheelX, wheelY, wheelR - bw * 0.004f, paint)
        paint.shader = null
        if (pressed == T_WHEEL) {
            paint.color = finish.pressWheel
            c.drawCircle(wheelX, wheelY, wheelR, paint)
        }

        drawHole(c, wheelX, wheelY, wheelHole, wheelWall, wheelEdge)

        paint.color = finish.floor
        c.drawCircle(wheelX, wheelY, centerHole, paint)
        shade(centerShader)
        c.drawCircle(wheelX, wheelY, centerR - bw * 0.004f, paint)
        paint.shader = null
        if (pressed == T_CENTER) {
            paint.color = finish.pressCenter
            c.drawCircle(wheelX, wheelY, centerR, paint)
        }
        drawHole(c, wheelX, wheelY, centerHole, centerWall, centerEdge)
    }

    // ---- touch -----------------------------------------------------------------------------

    private fun hitTest(x: Float, y: Float): Int {
        val d = hypot(x - wheelX, y - wheelY)
        if (d <= centerR * 1.05f) return T_CENTER
        if (d <= wheelR * 1.12f) return T_WHEEL
        for (i in 0 until 4) {
            if (hypot(x - btnX[i], y - btnY) <= btnR * 1.2f) return i
        }
        return T_NONE
    }

    private fun angle(x: Float, y: Float) = Math.toDegrees(atan2(y - wheelY, x - wheelX).toDouble()).toFloat()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                litAtDown = lit
                wake()
                target = hitTest(x, y)
                pressed = target
                downX = x
                downY = y
                holdFired = false
                accum = 0f
                lastAngle = angle(x, y)
                if (target == T_CENTER || target == B_PREV || target == B_NEXT || target == B_PLAY) {
                    handler.postDelayed(holdRunnable, if (target == B_PLAY) 2000 else HOLD_MS)
                }
                if (target in 0..3) feedback(HapticFeedbackConstants.VIRTUAL_KEY)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                // Sliding from the centre button onto the ring turns it into a scroll.
                if (target == T_CENTER && !holdFired && hypot(x - downX, y - downY) > centerR * 0.6f &&
                    hypot(x - wheelX, y - wheelY) > centerR
                ) {
                    handler.removeCallbacks(holdRunnable)
                    target = T_WHEEL
                    pressed = T_WHEEL
                    lastAngle = angle(x, y)
                    accum = 0f
                }
                if (target == T_WHEEL) {
                    if (hypot(x - wheelX, y - wheelY) < centerR * 0.5f) return true // too close to judge
                    val a = angle(x, y)
                    var d = a - lastAngle
                    if (d > 180) d -= 360
                    if (d < -180) d += 360
                    lastAngle = a
                    accum += d
                    val now = System.currentTimeMillis()
                    while (abs(accum) >= WHEEL_STEP_DEG) {
                        val dir = if (accum > 0) 1 else -1
                        accum -= dir * WHEEL_STEP_DEG
                        wake()
                        if (lcd.scroll(dir, now)) tick()
                    }
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(holdRunnable)
                val t = target
                target = T_NONE
                pressed = T_NONE
                if (seekDir != 0) {
                    seekDir = 0
                    handler.removeCallbacks(seekRunnable)
                } else if (!holdFired && t == hitTest(x, y)) {
                    press(t)
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(holdRunnable)
                seekDir = 0
                handler.removeCallbacks(seekRunnable)
                target = T_NONE
                pressed = T_NONE
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun press(t: Int) {
        val pb = host.playback()
        val ps = host.service()
        when (t) {
            T_CENTER -> {
                tick()
                lcd.select()
            }
            B_MENU -> {
                tick()
                lcd.menu()
            }
            B_PLAY -> {
                tick()
                if (pb != null && pb.hasTrack()) {
                    pb.toggle()
                } else if (ps != null && !host.library().isEmpty()) {
                    ps.playQueue(host.library().songs(), 0)
                    lcd.showNowPlaying()
                }
            }
            B_PREV -> {
                tick()
                pb?.previous()
            }
            B_NEXT -> {
                tick()
                pb?.next()
            }
        }
        invalidate()
    }

    private fun onHold() {
        holdFired = true
        val ps = host.playback()
        when (target) {
            T_CENTER -> {
                feedback(HapticFeedbackConstants.LONG_PRESS)
                lcd.selectHold()
            }
            B_PLAY -> {
                // Holding play/pause puts the iPod to sleep.
                feedback(HapticFeedbackConstants.LONG_PRESS)
                if (ps != null && ps.isPlayingOrLoading()) ps.toggle()
                lit = false
                updateTilt()
            }
            B_PREV, B_NEXT -> {
                if (ps != null && ps.hasTrack()) {
                    seekDir = if (target == B_NEXT) 1 else -1
                    handler.post(seekRunnable)
                }
            }
        }
        invalidate()
    }

    private fun tick() {
        if (!host.clickerOn()) return
        host.clicker().click()
        feedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun feedback(type: Int) {
        if (host.clickerOn()) performHapticFeedback(type)
    }

    // ---- backlight -------------------------------------------------------------------------

    /** Any touch lights the backlight for the configured time, unless it was turned off. */
    fun wake() {
        lastActivity = System.currentTimeMillis()
        if (forcedOff) return
        val s = host.backlightSeconds()
        val was = lit
        lit = s != 0
        if (was != lit) {
            invalidate()
            updateTilt()
        }
        handler.removeCallbacks(backlightCheck)
        if (s > 0) handler.postDelayed(backlightCheck, s * 1000L + 50)
    }

    fun toggleBacklight() {
        // The touch that selected the menu item already woke the light, so toggle from before it.
        lit = !litAtDown
        litAtDown = lit
        forcedOff = !lit
        updateTilt()
        handler.removeCallbacks(backlightCheck)
        lastActivity = System.currentTimeMillis()
        val s = host.backlightSeconds()
        if (lit && s > 0) handler.postDelayed(backlightCheck, s * 1000L + 50)
        invalidate()
    }

    private fun checkBacklight() {
        val s = host.backlightSeconds()
        if (s > 0 && System.currentTimeMillis() - lastActivity >= s * 1000L) {
            lit = false
            invalidate()
            updateTilt()
        }
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    private companion object {
        /** LCD width and the black border around it, in units of [bw]. */
        const val LCD_W = 0.85f
        const val BEZEL = 0.0175f
        /** Height of the face's content in units of [bw], without spare space. */
        const val CONTENT_H = 0.07f + LCD_W * Lcd.H / Lcd.W + 2 * BEZEL + 0.06f + 0.18f + 0.06f + 0.72f + 0.08f
        const val WHEEL_STEP_DEG = 15f
        const val HOLD_MS = 650L
        const val SEEK_REPEAT_MS = 180L
        const val GLOW = 0xFFFF4A26.toInt()

        const val B_PREV = 0
        const val B_MENU = 1
        const val B_PLAY = 2
        const val B_NEXT = 3
        const val T_NONE = -1
        const val T_WHEEL = 10
        const val T_CENTER = 11

        /** Mixes colour a towards b by t (0-1). */
        fun blend(a: Int, b: Int, t: Float): Int {
            fun mix(shift: Int): Int {
                val x = (a shr shift) and 0xFF
                val y = (b shr shift) and 0xFF
                return (x + (y - x) * t).roundToInt()
            }
            return 0xFF000000.toInt() or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
        }

        /** The colour with its opacity scaled by k. */
        fun fade(c: Int, k: Float) = ((c ushr 24) * k).roundToInt() shl 24 or (c and 0xFFFFFF)

        /** A triangle pointing left (dir -1) or right (dir 1), starting at x. */
        fun tri(p: Path, x: Float, cy: Float, s: Float, dir: Int) {
            val w = s * 1.4f
            if (dir > 0) {
                p.moveTo(x, cy - s)
                p.lineTo(x + w, cy)
                p.lineTo(x, cy + s)
            } else {
                p.moveTo(x + w, cy - s)
                p.lineTo(x, cy)
                p.lineTo(x + w, cy + s)
            }
            p.close()
        }
    }
}
