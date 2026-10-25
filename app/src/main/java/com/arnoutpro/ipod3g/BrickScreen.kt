package com.arnoutpro.ipod3g

import android.graphics.Canvas
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/** Extras > Games > Brick: turn the wheel to move the paddle, select to serve. */
class BrickScreen(lcd: Lcd) : Screen(lcd, "Brick") {
    private val bricks = Array(ROWS) { BooleanArray(COLS) }
    private var w = Lcd.W.toFloat()
    private var h = (Lcd.H - Lcd.TITLE_H).toFloat()
    private var paddleX = (Lcd.W - PADDLE_W) / 2f
    private var bx = 0f
    private var by = 0f
    private var vx = 0f
    private var vy = 0f
    private var served = false
    private var lives = 3
    private var score = 0
    private var level = 1
    private var left = 0
    private var over = false
    private var lastFrame = 0L

    init {
        newLevel()
    }

    private fun newLevel() {
        for (row in bricks) row.fill(true)
        left = ROWS * COLS
        served = false
    }

    override fun scroll(delta: Int, now: Long): Boolean {
        val old = paddleX
        paddleX = (paddleX + delta * 14).coerceIn(0f, maxOf(0f, w - PADDLE_W))
        return old != paddleX
    }

    override fun select() {
        if (over) {
            over = false
            lives = 3
            score = 0
            level = 1
            newLevel()
            return
        }
        if (!served) {
            served = true
            val speed = 150f + level * 20
            vx = speed * 0.6f * if (Math.random() < 0.5) -1 else 1
            vy = -speed
        }
    }

    override fun frameDelay(now: Long): Long = 16

    override fun draw(c: Canvas, w: Int, h: Int, now: Long) {
        this.w = w.toFloat()
        this.h = h.toFloat()
        val dt = if (lastFrame == 0L) 0f else minOf(0.05f, (now - lastFrame) / 1000f)
        lastFrame = now
        val py = this.h - 16
        if (!served) {
            bx = paddleX + PADDLE_W / 2f - BALL / 2f
            by = py - BALL - 1
        } else if (!over) {
            step(dt, py)
        }

        c.drawText("Score $score", 8f, 18f, lcd.small)
        val lv = "Lives $lives"
        c.drawText(lv, w - 8 - lcd.small.measureText(lv), 18f, lcd.small)

        val x0 = brickLeft()
        for (r in 0 until ROWS) {
            for (col in 0 until COLS) {
                if (!bricks[r][col]) continue
                val x = x0 + col * (BRICK_W + GAP)
                val y = TOP + r * (BRICK_H + GAP)
                c.drawRect(x, y, x + BRICK_W, y + BRICK_H, if (r % 2 == 0) lcd.ink else lcd.dither)
                if (r % 2 == 1) c.drawRect(x, y, x + BRICK_W, y + BRICK_H, lcd.stroke)
            }
        }

        c.drawRect(paddleX, py, paddleX + PADDLE_W, py + PADDLE_H, lcd.ink)
        if (!over) c.drawRect(bx, by, bx + BALL, by + BALL, lcd.ink)

        val msg = if (over) "Game Over" else if (!served) "Press Select" else null
        if (msg != null) {
            val tw = lcd.text.measureText(msg)
            c.drawText(msg, (w - tw) / 2f, h * 0.68f, lcd.text)
        }
    }

    private fun brickLeft() = (w - (COLS * BRICK_W + (COLS - 1) * GAP)) / 2f

    private fun step(dt: Float, py: Float) {
        bx += vx * dt
        by += vy * dt
        if (bx < 0) {
            bx = 0f
            vx = abs(vx)
        }
        if (bx + BALL > w) {
            bx = w - BALL
            vx = -abs(vx)
        }
        if (by < 22) {
            by = 22f
            vy = abs(vy)
        }

        // Paddle: the bounce angle depends on where the ball lands.
        if (vy > 0 && by + BALL >= py && by + BALL <= py + PADDLE_H + 6 &&
            bx + BALL >= paddleX && bx <= paddleX + PADDLE_W
        ) {
            val speed = hypot(vx, vy)
            val hit = (((bx + BALL / 2f) - (paddleX + PADDLE_W / 2f)) / (PADDLE_W / 2f)).coerceIn(-0.85f, 0.85f)
            vx = speed * hit
            vy = -sqrt(maxOf(1f, speed * speed - vx * vx))
            by = py - BALL
        }

        hitBrick()
        if (left == 0) {
            level++
            newLevel()
        }
        if (by > h) {
            lives--
            served = false
            if (lives <= 0) over = true
        }
    }

    /** Breaks the first brick the ball touches, if any, and bounces off it. */
    private fun hitBrick() {
        val x0 = brickLeft()
        for (r in 0 until ROWS) {
            for (col in 0 until COLS) {
                if (!bricks[r][col]) continue
                val x = x0 + col * (BRICK_W + GAP)
                val y = TOP + r * (BRICK_H + GAP)
                if (bx + BALL > x && bx < x + BRICK_W && by + BALL > y && by < y + BRICK_H) {
                    bricks[r][col] = false
                    left--
                    score += (ROWS - r) * 10
                    val overlapX = minOf(bx + BALL - x, x + BRICK_W - bx)
                    val overlapY = minOf(by + BALL - y, y + BRICK_H - by)
                    if (overlapX < overlapY) vx = -vx else vy = -vy
                    return
                }
            }
        }
    }

    private companion object {
        const val COLS = 8
        const val ROWS = 4
        const val BRICK_W = 36f
        const val BRICK_H = 10f
        const val GAP = 3f
        const val TOP = 26f
        const val PADDLE_W = 52f
        const val PADDLE_H = 6f
        const val BALL = 6f
    }
}
