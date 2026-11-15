package com.cosyhome.tv;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Path;
import android.graphics.Shader;

/**
 * Mountains, Alps and Dolomites: two ranges of rocky peaks with snow that grows in winter, a fir
 * forest, an alpine meadow with a wooden chalet.
 */
final class MountainsLand extends Land {

    /** The far range, its peaks as x, y pairs; snow caps go on every local top. */
    private static final float[] FAR = {0, 430, 110, 380, 200, 420, 330, 290, 420, 370, 520, 330, 610, 400, 720, 320,
            850, 232, 960, 340, 1060, 300, 1170, 380, 1290, 268, 1400, 330, 1500, 300, 1610, 380, 1730, 318, 1840, 360,
            1920, 340};
    private static final float[] NEAR = {0, 520, 140, 468, 260, 500, 380, 430, 500, 480, 600, 452, 700, 500, 800, 466,
            900, 520, 1010, 468, 1110, 500, 1240, 438, 1360, 490, 1480, 458, 1600, 505, 1720, 470, 1840, 500, 1920, 480};

    private static final float CHALET_X = 1420, CHALET_W = 112;

    private final Path far = Scene.ridge(FAR), near = Scene.ridge(NEAR);
    private final Path forest = band(585, 18, 170, 10, 63);
    private final Path meadow = band(678, 14, 260, 6, 97);
    private final Path pasture = Scene.curve(0, 800,
            300, 770, 640, 790, 900, 806, 1160, 822, 1440, 780, 1700, 786, 1800, 790, 1880, 796, 1920, 800);
    private final Path front = Scene.curve(0, 930,
            400, 896, 800, 912, 1100, 930, 1400, 946, 1700, 908, 1920, 916);

    MountainsLand() {
        super(Kind.MOUNTAINS);
        float b = meadowTop(CHALET_X + CHALET_W / 2) + 6;
        smokeX = CHALET_X + CHALET_W * 0.74f;
        smokeY = b - 46 - 58;
        treeX = 1600;
        treeBase = meadowTop(1600) + 10;
        treeH = 160;
        candles = new float[][]{{1330, b - 2, 24}, {1376, b + 4, 34}, {1560, b + 6, 20}};
        eaveX0 = CHALET_X - 6;
        eaveX1 = CHALET_X + CHALET_W + 6;
        eaveY = b - 46;
        skyline = FAR;
    }

    private static float meadowTop(float x) {
        return 678 + 14 * (float) Math.sin(x / 260 + 1) + 6 * (float) Math.sin(x / 97);
    }

    private static float forestTop(float x) {
        return 585 + 18 * (float) Math.sin(x / 170) + 10 * (float) Math.sin(x / 63);
    }

    /** A wavy band's upper edge, y0 + a1 sin(x/p1) + a2 sin(x/p2), closed to the bottom. */
    private static Path band(float y0, float a1, float p1, float a2, float p2) {
        Path p = new Path();
        for (int x = 0; x <= Scene.W; x += 16) {
            float y = y0 + a1 * (float) Math.sin(x / p1 + (y0 > 600 ? 1 : 0)) + a2 * (float) Math.sin(x / p2);
            if (x == 0) p.moveTo(x, y);
            else p.lineTo(x, y);
        }
        p.lineTo(Scene.W, Scene.H + 400);
        p.lineTo(0, Scene.H + 400);
        p.close();
        return p;
    }

    /** How far down the peaks the snow reaches, by season. */
    private static float snowLine(Season season) {
        switch (season) {
            case CHRISTMAS:
            case WINTER:
                return 0.55f;
            case SPRING:
                return 0.34f;
            case SUMMER:
                return 0.16f;
            default:
                return 0.26f;
        }
    }

    @Override
    void draw(Scene s, Canvas c) {
        Scene.Palette pal = s.pal;
        int rock = s.lit(0xFF8E929C), snow = s.lit(0xFFF4F7FA);
        if (s.season == Season.HALLOWEEN) {
            rock = 0xFF2A1C3A;
            snow = 0xFF6C5A80;
        }
        float line = snowLine(s.season);
        range(s, c, far, FAR, s.hazed(rock, 0.55f), s.hazed(snow, 0.45f), line);
        range(s, c, near, NEAR, s.hazed(Scene.mix(rock, 0xFF000000, 0.12f), 0.28f), s.hazed(snow, 0.25f), line * 0.8f);

        // The fir forest on the lower slopes, its edge a row of spires.
        int forestColor = s.hazed(Scene.mix(pal.h3, 0xFF14281E, s.snowy() ? 0.3f : 0.55f), 0.22f);
        if (s.season == Season.CHRISTMAS) forestColor = s.hazed(Scene.mix(pal.h3, 0xFF2A3A34, 0.5f), 0.2f);
        s.hill(c, forest, forestColor, 560, 700);
        for (int i = 0; i < 74; i++) {
            float x = i * 26 + 9 * (float) Math.sin(i * 2.3f);
            s.fir(c, x, forestTop(x) + 8, 36 + 14 * (float) Math.abs(Math.sin(i * 1.7f)), forestColor);
        }

        s.hill(c, meadow, pal.h1, 660, 800);
        float b = meadowTop(CHALET_X + CHALET_W / 2) + 6;
        s.houseGlow(c, CHALET_X + CHALET_W / 2, b - 30, 160);
        int fir = Scene.mix(pal.h3, 0xFF10241A, s.night() ? 0.3f : 0.6f);
        s.fir(c, 1330, meadowTop(1330) + 12, 92, fir);
        s.fir(c, 1300, meadowTop(1300) + 14, 70, fir);
        chalet(s, c, CHALET_X, b);
        s.fir(c, 1640, meadowTop(1640) + 10, 84, fir);
        s.fir(c, 1672, meadowTop(1672) + 12, 62, fir);
        s.fir(c, 240, meadowTop(240) + 10, 74, fir);
        s.fir(c, 270, meadowTop(270) + 12, 56, fir);
        guests(s, c, 1760, meadowTop(1760) + 10, 0.9f);

        s.hill(c, pasture, Scene.mix(pal.h2, pal.h3, 0.15f), 770, 960);
        s.hill(c, front, pal.h3, 900, 1080);
    }

    /** A range of peaks: rock shaded from left to right, snow down from every top. */
    private void range(Scene s, Canvas c, Path shape, float[] pts, int rock, int snow, float line) {
        s.shader(new LinearGradient(0, 0, Scene.W, 0, new int[]{Scene.mix(rock, 0xFFFFFFFF, 0.08f), rock,
                Scene.mix(rock, 0xFF000000, 0.1f)}, null, Shader.TileMode.CLAMP));
        c.drawPath(shape, s.paint);
        s.paint.setShader(null);
        for (int i = 2; i < pts.length - 2; i += 2) {
            float y = pts[i + 1];
            if (y >= pts[i - 1] || y >= pts[i + 3]) continue;
            float tx = pts[i], lx = pts[i - 2], ly = pts[i - 1], rx = pts[i + 2], ry = pts[i + 3];
            // Down each flank by the snow line, then a ragged lower edge between the two.
            float l2x = tx + (lx - tx) * line, l2y = y + (ly - y) * line;
            float r2x = tx + (rx - tx) * line, r2y = y + (ry - y) * line;
            float depth = Math.max(l2y, r2y) - y;
            Path cap = new Path();
            cap.moveTo(tx, y);
            cap.lineTo(r2x, r2y);
            cap.lineTo(tx + (r2x - tx) * 0.55f, y + depth * 0.62f);
            cap.lineTo(tx + (r2x - tx) * 0.2f, y + depth * 0.95f);
            cap.lineTo(tx - (tx - l2x) * 0.15f, y + depth * 0.7f);
            cap.lineTo(tx - (tx - l2x) * 0.5f, y + depth * 0.98f);
            cap.lineTo(l2x, l2y);
            cap.close();
            s.fill(c, cap, snow);
        }
    }

    /** A wooden chalet on a stone base, a steep shingled gable with deep eaves, a balcony. */
    private void chalet(Scene s, Canvas c, float x, float b) {
        int wood = s.lit(0xFF8A5A36), stone = s.lit(0xFFB4ADA2), shingles = s.lit(0xFF4A3B33);
        if (s.season == Season.HALLOWEEN) {
            wood = 0xFF2A1E2E;
            stone = 0xFF3A3242;
            shingles = 0xFF160E1C;
        }
        float w = CHALET_W, wall = 46;
        s.paint.setColor(stone);
        c.drawRect(x, b - 14, x + w, b, s.paint);
        s.paint.setColor(wood);
        c.drawRect(x, b - wall, x + w, b - 14, s.paint);
        // Planks.
        s.paint.setColor(Scene.mix(wood, 0xFF000000, 0.18f));
        for (float yy = b - wall + 6; yy < b - 14; yy += 7) c.drawRect(x, yy, x + w, yy + 1.2f, s.paint);
        // The chimney, then the roof over it.
        s.paint.setColor(stone);
        c.drawRect(x + w * 0.7f, b - wall - 58, x + w * 0.78f, b - wall - 20, s.paint);
        s.gable(c, x - 12, b - wall + 2, x + w + 12, b - wall - 52, shingles);
        // The gable's wooden front, a balcony across it.
        Path front = new Path();
        front.moveTo(x + 8, b - wall);
        front.lineTo(x + w / 2, b - wall - 40);
        front.lineTo(x + w - 8, b - wall);
        front.close();
        s.fill(c, front, Scene.mix(wood, 0xFFFFFFFF, 0.06f));
        s.window(c, x + w / 2 - 6, b - wall - 22, x + w / 2 + 6, b - wall - 8);
        s.paint.setColor(Scene.mix(wood, 0xFF000000, 0.3f));
        c.drawRect(x - 4, b - wall + 4, x + w + 4, b - wall + 8, s.paint);
        for (float px = x; px <= x + w; px += 10) c.drawRect(px, b - wall + 8, px + 2, b - wall + 18, s.paint);
        c.drawRect(x - 4, b - wall + 18, x + w + 4, b - wall + 21, s.paint);
        s.window(c, x + 14, b - wall + 24, x + 28, b - 18);
        s.window(c, x + w - 28, b - wall + 24, x + w - 14, b - 18);
        s.paint.setColor(Scene.mix(wood, 0xFF000000, 0.45f));
        c.drawRect(x + w / 2 - 8, b - 26, x + w / 2 + 8, b, s.paint);
    }
}
