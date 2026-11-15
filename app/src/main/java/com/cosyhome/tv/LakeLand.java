package com.cosyhome.tv;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;

/**
 * A lake between mountains, Como or Garda style: wooded slopes falling into still water that mirrors
 * them, far peaks between, a village of pastel houses and a bell tower on the shore.
 */
final class LakeLand extends Land {

    private static final float WATER = 642, SHORE = 776;
    /** The village's houses: x, width, height; and their colours by daylight. */
    private static final float[][] HOUSES = {
            {1188, 40, 46}, {1226, 34, 58}, {1258, 46, 50}, {1302, 38, 62}, {1338, 44, 48}, {1380, 36, 56},
            {1470, 42, 44}, {1510, 36, 52}};
    private static final int[] HOUSE_COLORS = {0xFFE8C88A, 0xFFE6B0A0, 0xFFF0E6D2, 0xFFD9A066, 0xFFE8D9B0,
            0xFFE2B8A8, 0xFFEFE2C4, 0xFFD7A878};
    private static final float TOWER_X = 1424, VILLAGE_BASE = WATER + 12;

    private final Path farPeaks = Scene.ridge(540, 640, 640, 560, 720, 510, 790, 470, 860, 500, 940, 452, 1010, 486,
            1080, 470, 1160, 520, 1240, 600, 1300, 640);
    private final Path left = Scene.curve(0, 330, 200, 360, 420, 470, 560, 560, 620, 598, 690, 632, 760, 646);
    private final Path right = rightMountain();
    private final Path shore = shore();
    private final Path meadow = Scene.curve(0, SHORE, 300, 760, 640, 770, 900, 784, 1160, 796, 1440, 762, 1920, 772);
    private final Path front = Scene.curve(0, 920, 400, 890, 800, 904, 1100, 922, 1400, 936, 1700, 900, 1920, 908);

    LakeLand() {
        super(Kind.LAKE);
        smokeX = 1320;
        smokeY = VILLAGE_BASE - 62 - 20;
        treeX = 1130;
        treeBase = VILLAGE_BASE + 2;
        treeH = 130;
        candles = new float[][]{{1160, VILLAGE_BASE + 4, 18}, {1450, VILLAGE_BASE + 6, 22}, {1556, VILLAGE_BASE + 6, 16}};
        eaveX0 = 1188;
        eaveX1 = 1416;
        eaveY = VILLAGE_BASE - 46;
        skyline = new float[]{0, 330, 200, 372, 400, 452, 540, 545, 640, 560, 720, 510, 790, 470, 860, 500, 940, 452,
                1010, 486, 1080, 470, 1160, 506, 1260, 520, 1400, 500, 1520, 452, 1700, 392, 1920, 360};
    }

    /** The village's strip of land along the water. */
    private static Path shore() {
        Path p = new Path();
        p.moveTo(1060, VILLAGE_BASE + 16);
        p.lineTo(1100, VILLAGE_BASE - 4);
        p.cubicTo(1300, VILLAGE_BASE - 8, 1600, VILLAGE_BASE - 6, 1920, VILLAGE_BASE - 2);
        p.lineTo(1920, VILLAGE_BASE + 22);
        p.cubicTo(1600, VILLAGE_BASE + 18, 1300, VILLAGE_BASE + 24, 1060, VILLAGE_BASE + 16);
        p.close();
        return p;
    }

    private static Path rightMountain() {
        Path p = new Path();
        p.moveTo(1100, 652);
        p.cubicTo(1220, 610, 1320, 560, 1400, 512);
        p.cubicTo(1520, 448, 1700, 386, 1920, 360);
        p.lineTo(1920, Scene.H + 400);
        p.lineTo(1100, Scene.H + 400);
        p.close();
        return p;
    }

    @Override
    void draw(Scene s, Canvas c) {
        Scene.Palette pal = s.pal;
        int rock = s.lit(0xFF8E96A2), snow = s.lit(0xFFF2F6FA);
        int woodsFar = s.hazed(Scene.mix(pal.h3, 0xFF2A4A44, 0.45f), 0.32f);
        int woodsNear = s.hazed(Scene.mix(pal.h3, 0xFF1E3A30, 0.5f), 0.14f);
        if (s.season == Season.HALLOWEEN) {
            rock = 0xFF2A1C3A;
            snow = 0xFF6C5A80;
            woodsFar = 0xFF22162E;
            woodsNear = 0xFF180F22;
        }
        int peaks = s.hazed(rock, 0.55f);

        // The water first, as the sky's mirror; the land then stands over its far edge.
        s.shader(new LinearGradient(0, WATER, 0, SHORE, new int[]{pal.s3, Scene.mix(pal.s2, pal.s3, 0.5f)}, null,
                Shader.TileMode.CLAMP));
        c.drawRect(0, WATER, Scene.W, Scene.H + 400, s.paint);
        s.paint.setShader(null);
        // The mountains upside down in it, softened.
        c.save();
        c.clipRect(0, WATER, Scene.W, SHORE);
        c.saveLayerAlpha(0, WATER, Scene.W, SHORE, 120);
        c.scale(1, -1, 0, WATER);
        mountains(s, c, peaks, snow, woodsFar, woodsNear);
        c.restore();
        c.restore();
        // A tint of deep water over the reflection, darker towards us.
        int deep = s.night() ? 0xFF0A1428 : s.lit(0xFF2E5A6E);
        s.shader(new LinearGradient(0, WATER, 0, SHORE, Scene.alpha(deep, 0.15f), Scene.alpha(deep, 0.6f),
                Shader.TileMode.CLAMP));
        c.drawRect(0, WATER, Scene.W, SHORE, s.paint);
        s.paint.setShader(null);

        mountains(s, c, peaks, snow, woodsFar, woodsNear);

        // The village on its strip of shore.
        s.hill(c, shore, Scene.mix(pal.h2, pal.h3, 0.3f), VILLAGE_BASE - 8, VILLAGE_BASE + 40);
        s.houseGlow(c, 1340, VILLAGE_BASE - 24, 220);
        int cypress = Scene.mix(pal.h3, 0xFF0E1A12, s.night() ? 0.3f : 0.55f);
        s.cypress(c, 1170, VILLAGE_BASE, 52, cypress);
        village(s, c);
        s.cypress(c, 1458, VILLAGE_BASE + 2, 64, cypress);
        s.cypress(c, 1566, VILLAGE_BASE + 2, 48, cypress);
        guests(s, c, 1600, VILLAGE_BASE + 4, 0.55f);

        s.hill(c, meadow, Scene.mix(pal.h1, pal.h2, 0.4f), 760, 940);
        s.hill(c, front, pal.h3, 890, 1080);
    }

    /** The mountains, down to the water's edge and no further: the water is already there below it. */
    private void mountains(Scene s, Canvas c, int peaks, int snow, int woodsFar, int woodsNear) {
        c.save();
        c.clipRect(0, -400, Scene.W, WATER + 1);
        s.hill(c, farPeaks, peaks, 450, 640);
        // Snow on the far tops, more of it in winter.
        float line = s.snowy() ? 0.5f : s.season == Season.SUMMER ? 0.15f : 0.28f;
        float[][] tops = {{790, 470, 720, 510, 860, 500}, {940, 452, 860, 500, 1010, 486}, {1080, 470, 1010, 486, 1160, 520}};
        for (float[] t : tops) {
            Path cap = new Path();
            cap.moveTo(t[0], t[1]);
            cap.lineTo(t[0] + (t[4] - t[0]) * line, t[1] + (t[5] - t[1]) * line);
            cap.lineTo(t[0] + (t[4] - t[0]) * line * 0.3f, t[1] + (t[5] - t[1]) * line * 1.3f);
            cap.lineTo(t[0] - (t[0] - t[2]) * line * 0.2f, t[1] + (t[3] - t[1]) * line * 0.9f);
            cap.lineTo(t[0] + (t[2] - t[0]) * line, t[1] + (t[3] - t[1]) * line);
            cap.close();
            s.fill(c, cap, s.hazed(snow, 0.4f));
        }
        s.hill(c, left, woodsFar, 330, 660);
        s.hill(c, right, woodsNear, 360, 660);
        c.restore();
    }

    /** Pastel houses shoulder to shoulder, terracotta roofs, a bell tower. */
    private void village(Scene s, Canvas c) {
        float b = VILLAGE_BASE;
        boolean spooky = s.season == Season.HALLOWEEN;
        int roof = spooky ? 0xFF180F20 : s.lit(0xFFB0563A);
        // The bell tower behind the houses.
        int stone = spooky ? 0xFF2E2440 : s.lit(0xFFE2D2B4);
        s.paint.setColor(stone);
        c.drawRect(TOWER_X, b - 118, TOWER_X + 26, b, s.paint);
        s.paint.setColor(Scene.mix(stone, 0xFF000000, 0.2f));
        c.drawRect(TOWER_X + 18, b - 118, TOWER_X + 26, b, s.paint);
        s.paint.setColor(Scene.mix(stone, 0xFF000000, 0.55f));
        c.drawRect(TOWER_X + 7, b - 108, TOWER_X + 19, b - 94, s.paint);
        c.drawCircle(TOWER_X + 13, b - 108, 6, s.paint);
        Path spire = new Path();
        spire.moveTo(TOWER_X - 3, b - 118);
        spire.lineTo(TOWER_X + 13, b - 146);
        spire.lineTo(TOWER_X + 29, b - 118);
        spire.close();
        s.fill(c, spire, roof);
        // The smoking chimney, on the fourth house.
        s.paint.setColor(stone);
        c.drawRect(1314, b - 62 - 20, 1326, b - 62, s.paint);
        for (int i = 0; i < HOUSES.length; i++) {
            float x = HOUSES[i][0], w = HOUSES[i][1], ht = HOUSES[i][2];
            int wall = spooky ? 0xFF2E2440 : s.lit(HOUSE_COLORS[i]);
            s.paint.setColor(wall);
            c.drawRect(x, b - ht, x + w, b, s.paint);
            s.paint.setColor(Scene.mix(wall, 0xFF000000, 0.12f));
            c.drawRect(x + w - 5, b - ht, x + w, b, s.paint);
            s.roof(c, x - 3, b - ht + 2, x + w + 3, b - ht - 10, roof);
            for (float wy = b - ht + 10; wy < b - 14; wy += 16) {
                for (float wx = x + 6; wx < x + w - 10; wx += 13) s.window(c, wx, wy, wx + 6, wy + 8);
            }
        }
    }

    @Override
    void frame(Scene s, Canvas c, Paint fx, float t) {
        boolean clear = s.sky != Season.Fx.RAIN && s.sky != Season.Fx.FOG && s.season != Season.HALLOWEEN;
        float lx = s.phase == SceneView.Phase.DAWN ? 1180 : s.phase == SceneView.Phase.DUSK ? 760 : 1500;
        ripples(s, c, fx, t, WATER + 14, SHORE - 6, clear ? lx : -9999, 80);
        if (s.glow() > 0) {
            // The village's lights trembling on the water.
            for (int i = 0; i < 14; i++) {
                float x = 1192 + i * 25 + 4 * (float) Math.sin(t * 0.7f + i);
                float len = 18 + 14 * (float) Math.abs(Math.sin(t * 0.9f + i * 1.3f));
                fx.setColor(Scene.alpha(s.pal.win, 0.18f + 0.12f * (float) Math.sin(t * 2 + i)));
                c.drawRect(x, WATER + 22, x + 3, WATER + 22 + len, fx);
            }
        }
        sailboat(s, c, fx, t, 300, 1080, 704, 40, 300);
    }
}
