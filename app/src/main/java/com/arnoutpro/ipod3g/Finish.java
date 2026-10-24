package com.arnoutpro.ipod3g;

/** The colours of the iPod's plastic, wheel and buttons under the acrylic. */
public final class Finish {
    final int[] body;
    final int bezelRim, bezel, floor;
    final int wheelHi, wheelLo, centerTop, centerBottom, btnTop, btnBottom;
    final int glyphOff;
    final int pressBtn, pressWheel, pressCenter;
    /** Scales the hole shadows, which barely show on black and need more weight there. */
    final float wallWeight;
    final int edgeDark, edgeLight;
    final int[] gloss;

    private Finish(int[] body, int bezelRim, int bezel, int floor,
                   int wheelHi, int wheelLo, int centerTop, int centerBottom, int btnTop, int btnBottom,
                   int glyphOff, int pressBtn, int pressWheel, int pressCenter,
                   float wallWeight, int edgeDark, int edgeLight, int[] gloss) {
        this.body = body;
        this.bezelRim = bezelRim;
        this.bezel = bezel;
        this.floor = floor;
        this.wheelHi = wheelHi;
        this.wheelLo = wheelLo;
        this.centerTop = centerTop;
        this.centerBottom = centerBottom;
        this.btnTop = btnTop;
        this.btnBottom = btnBottom;
        this.glyphOff = glyphOff;
        this.pressBtn = pressBtn;
        this.pressWheel = pressWheel;
        this.pressCenter = pressCenter;
        this.wallWeight = wallWeight;
        this.edgeDark = edgeDark;
        this.edgeLight = edgeLight;
        this.gloss = gloss;
    }

    /** The real thing: white plastic, a touch cooler and darker than pure white under the acrylic. */
    static final Finish WHITE = new Finish(
            new int[]{0xFFFAFBFB, 0xFFF3F4F4, 0xFFE8E9E8}, 0xFFE2E2DF, 0xFF26292B, 0xFFE6E7E5,
            0xFFFFFFFF, 0xFFF0F0EE, 0xFFF1F1EF, 0xFFFFFFFF, 0xFFF2F2F0, 0xFFFFFFFF,
            0xFFA9A9A4, 0x22000000, 0x0C000000, 0x18000000,
            1f, 0x1A, 0x90,
            new int[]{0x20FFFFFF, 0x0AFFFFFF, 0x00FFFFFF, 0x07FFFFFF, 0x00FFFFFF});

    /** It never existed: gloss black plastic with a graphite wheel and buttons. */
    static final Finish BLACK = new Finish(
            new int[]{0xFF202124, 0xFF16171A, 0xFF0D0E10}, 0xFF34363A, 0xFF08090A, 0xFF070809,
            0xFF34363A, 0xFF1F2023, 0xFF1E1F22, 0xFF2D2E32, 0xFF1D1E21, 0xFF2C2D31,
            0xFF505256, 0x30000000, 0x18000000, 0x28000000,
            2.6f, 0x50, 0x46,
            new int[]{0x1CFFFFFF, 0x08FFFFFF, 0x00FFFFFF, 0x06FFFFFF, 0x00FFFFFF});
}
