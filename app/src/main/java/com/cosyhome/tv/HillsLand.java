package com.cosyhome.tv;

import android.graphics.Canvas;
import android.graphics.Path;

/**
 * Rolling hills, Tuscan style: ridges fading into the sky, an avenue of cypresses up to a farmhouse
 * with a dovecote tower, an umbrella pine, vines on the near slope.
 */
final class HillsLand extends Land {

    private static final float HOUSE_X = 1470, HOUSE_BASE = 694, HOUSE_SCALE = 1.3f;

    private final Path mountains = Scene.curve(0, 600,
            160, 562, 300, 586, 420, 572, 560, 554, 660, 600, 820, 592,
            980, 584, 1080, 546, 1240, 560, 1400, 574, 1520, 538, 1700, 564, 1800, 578, 1870, 570, 1920, 574);
    private final Path far = Scene.curve(0, 650,
            220, 612, 420, 628, 640, 646, 860, 664, 1040, 612, 1280, 618, 1500, 624, 1700, 660, 1920, 640);
    private final Path mid = Scene.curve(0, 716,
            260, 680, 520, 700, 760, 722, 1000, 744, 1240, 680, 1480, 690, 1680, 698, 1820, 708, 1920, 700);
    private final Path near = Scene.curve(0, 830,
            300, 784, 640, 798, 900, 822, 1160, 846, 1440, 792, 1700, 798, 1800, 800, 1880, 808, 1920, 812);
    private final Path front = Scene.curve(0, 940,
            400, 900, 800, 918, 1100, 936, 1400, 954, 1700, 914, 1920, 922);

    HillsLand() {
        super(Kind.HILLS);
        smokeX = hx(1512);
        smokeY = hy(616);
        treeX = 1372;
        treeBase = 706;
        treeH = 190;
        candles = new float[][]{{1296, 700, 26}, {1350, 692, 38}, {1408, 704, 22}};
        eaveX0 = hx(1466);
        eaveX1 = hx(1585);
        eaveY = hy(648);
        skyline = new float[]{0, 600, 420, 572, 1080, 552, 1240, 560, 1520, 545, 1920, 574};
    }

    /** Where a point of the farmhouse drawing lands once the house is scaled up. */
    private static float hx(float x) {
        return HOUSE_X + 70 + (x - HOUSE_X - 70) * HOUSE_SCALE;
    }

    private static float hy(float y) {
        return HOUSE_BASE + (y - HOUSE_BASE) * HOUSE_SCALE;
    }

    @Override
    float lowSunY(float x) {
        return 612;
    }

    @Override
    void draw(Scene s, Canvas c) {
        Scene.Palette pal = s.pal;
        int haze = pal.s3;
        // Far to near, each layer a little less veiled by the air between it and us.
        int mountain = Scene.mix(pal.h1, haze, 0.62f), l1 = Scene.mix(pal.h1, haze, 0.3f), l2 = pal.h1,
                l3 = Scene.mix(pal.h2, pal.h3, 0.15f), l4 = pal.h3;
        s.hill(c, mountains, mountain, 560, 720);
        s.hill(c, far, l1, 615, 760);
        int cypress = Scene.mix(pal.h3, 0xFF0E1A12, s.night() ? 0.3f : 0.55f);
        int farCypress = Scene.mix(cypress, haze, 0.45f);
        s.cypress(c, 404, 634, 34, farCypress);
        s.cypress(c, 422, 633, 40, farCypress);
        s.cypress(c, 441, 634, 30, farCypress);
        s.cypress(c, 1006, 634, 32, farCypress);
        s.cypress(c, 1024, 632, 38, farCypress);

        s.hill(c, mid, l2, 680, 820);
        s.houseGlow(c, HOUSE_X + 70, HOUSE_BASE - 20, 170);
        // The white road winding up through the avenue.
        Path road = new Path();
        road.moveTo(1040, 760);
        road.cubicTo(1180, 744, 1300, 730, 1380, 716);
        road.cubicTo(1420, 709, 1450, 703, 1470, 698);
        s.stroke(c, road, Scene.alpha(Scene.mix(l2, 0xFFFFFFFF, s.night() ? 0.06f : 0.28f), 0.7f), 7);
        s.umbrellaPine(c, 800, 724, 1f, Scene.mix(cypress, l2, 0.12f));
        farmhouse(s, c);
        // The avenue: a cypress on each side of the road along the ridge, smaller as it nears the
        // farmhouse. Far ones first, so the nearer ones stand in front of them.
        for (int i = 7; i >= 0; i--) {
            float t = i / 7f;
            float x = 1110 + 330 * t, y = 742 - 40 * t;
            float hgt = 76 - 30 * t;
            s.cypress(c, x, y - 8 + 4 * t, hgt, cypress);
            s.cypress(c, x + 14, y + 10 - 6 * t, hgt * 1.06f, cypress);
        }
        s.cypress(c, 1668, 704, 70, cypress);
        s.cypress(c, 1690, 706, 54, cypress);
        guests(s, c, 1780, 720, 1f);

        s.hill(c, near, l3, 790, 980);
        s.rows(c, near, Scene.mix(l3, pal.h3, 0.5f), 0.22f);
        s.hill(c, front, l4, 910, 1080);
    }

    /** A long stone house, a dovecote tower, a low barn, terracotta roofs. */
    private void farmhouse(Scene s, Canvas c) {
        int stone = s.lit(0xFFEAD7B5), roof = s.lit(0xFFB8623C);
        if (s.season == Season.HALLOWEEN) {
            stone = 0xFF2C2238;
            roof = 0xFF1C1428;
        }
        int shade = Scene.mix(stone, 0xFF000000, 0.22f);
        float x = HOUSE_X, b = HOUSE_BASE;
        c.save();
        c.scale(HOUSE_SCALE, HOUSE_SCALE, x + 70, b);
        s.paint.setColor(stone);
        c.drawRect(x, b - 48, x + 112, b, s.paint);
        s.roof(c, x - 8, b - 46, x + 120, b - 70, roof);
        s.paint.setColor(Scene.mix(stone, 0xFF000000, 0.1f));
        c.drawRect(1512 - 6, 616, 1512 + 6, b - 58, s.paint);
        s.paint.setColor(stone);
        c.drawRect(x + 104, b - 96, x + 146, b, s.paint);
        s.paint.setColor(shade);
        c.drawRect(x + 132, b - 96, x + 146, b, s.paint);
        s.roof(c, x + 98, b - 94, x + 152, b - 114, roof);
        s.paint.setColor(shade);
        c.drawRect(x - 46, b - 26, x, b, s.paint);
        s.roof(c, x - 52, b - 24, x + 2, b - 36, roof);
        for (int i = 0; i < 3; i++) s.window(c, x + 16 + i * 32, b - 34, x + 26 + i * 32, b - 20);
        s.window(c, x + 116, b - 78, x + 124, b - 64);
        s.paint.setColor(Scene.mix(stone, 0xFF000000, 0.45f));
        c.drawRect(x + 112, b - 22, x + 126, b, s.paint);
        c.drawCircle(x + 119, b - 22, 7, s.paint);
        c.restore();
    }
}
