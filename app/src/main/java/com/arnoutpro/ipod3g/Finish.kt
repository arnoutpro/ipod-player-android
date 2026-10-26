package com.arnoutpro.ipod3g

/** The colours of the iPod's plastic, wheel and buttons under the acrylic. */
class Finish private constructor(
    val body: IntArray,
    val bezelRim: Int,
    val bezel: Int,
    val floor: Int,
    val wheelHi: Int,
    val wheelLo: Int,
    val centerTop: Int,
    val centerBottom: Int,
    val btnTop: Int,
    val btnBottom: Int,
    val glyphOff: Int,
    val pressBtn: Int,
    val pressWheel: Int,
    val pressCenter: Int,
    /** Scales the hole shadows, which barely show on black and need more weight there. */
    val wallWeight: Float,
    val edgeDark: Int,
    val edgeLight: Int,
    val gloss: IntArray,
) {
    companion object {
        /** The real thing: white plastic, a touch cooler and darker than pure white under the acrylic. */
        val WHITE = Finish(
            intArrayOf(0xFFFAFBFB.toInt(), 0xFFF3F4F4.toInt(), 0xFFE8E9E8.toInt()),
            0xFFE2E2DF.toInt(), 0xFF26292B.toInt(), 0xFFE6E7E5.toInt(),
            0xFFFFFFFF.toInt(), 0xFFF0F0EE.toInt(), 0xFFF1F1EF.toInt(), 0xFFFFFFFF.toInt(),
            0xFFF2F2F0.toInt(), 0xFFFFFFFF.toInt(),
            0xFFA9A9A4.toInt(), 0x22000000, 0x0C000000, 0x18000000,
            1f, 0x1A, 0x90,
            intArrayOf(0x20FFFFFF, 0x0AFFFFFF, 0x00FFFFFF, 0x07FFFFFF, 0x00FFFFFF),
        )

        /** It never existed: gloss black plastic with a graphite wheel and buttons. */
        val BLACK = Finish(
            intArrayOf(0xFF202124.toInt(), 0xFF16171A.toInt(), 0xFF0D0E10.toInt()),
            0xFF34363A.toInt(), 0xFF08090A.toInt(), 0xFF070809.toInt(),
            0xFF34363A.toInt(), 0xFF1F2023.toInt(), 0xFF1E1F22.toInt(), 0xFF2D2E32.toInt(),
            0xFF1D1E21.toInt(), 0xFF2C2D31.toInt(),
            0xFF505256.toInt(), 0x30000000, 0x18000000, 0x28000000,
            2.6f, 0x50, 0x46,
            intArrayOf(0x1CFFFFFF, 0x08FFFFFF, 0x00FFFFFF, 0x06FFFFFF, 0x00FFFFFF),
        )
    }
}
