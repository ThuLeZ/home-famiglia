package com.cosyhome.tv;

import android.graphics.Canvas;
import android.graphics.Path;

/**
 * The plain, Po valley style: a patchwork of fields running to a flat horizon with the Alps faint
 * behind it, rows of poplars, a farmstead with a porticoed barn. Fog lies over it on winter mornings.
 */
final class PlainsLand extends Land {

    private static final float HORIZON = 640, VX = 960;
    /** Where each band of fields starts, far to near; perspective makes them wider as they come close. */
    private static final float[] BANDS = {646, 660, 680, 708, 748, 804, 884, 1000, Scene.H + 400};
    private static final float FARM_X = 1300, FARM_BASE = 684, FARM_SCALE = 1.15f;

    private final Path alps = Scene.ridge(0, 612, 90, 596, 160, 606, 260, 578, 340, 600, 430, 586, 520, 604, 640, 590,
            720, 606, 840, 580, 930, 598, 1040, 588, 1150, 604, 1260, 574, 1360, 598, 1470, 584, 1580, 604, 1700, 582,
            1800, 600, 1920, 590);

    PlainsLand() {
        super(Kind.PLAINS);
        smokeX = fx(FARM_X + 150);
        smokeY = fy(FARM_BASE - 70);
        treeX = 1560;
        treeBase = 690;
        treeH = 150;
        candles = new float[][]{{1230, 690, 22}, {1272, 686, 30}, {1520, 688, 18}};
        eaveX0 = fx(FARM_X + 76);
        eaveX1 = fx(FARM_X + 206);
        eaveY = fy(FARM_BASE - 46);
        skyline = new float[]{0, 612, 260, 578, 840, 580, 1260, 574, 1700, 582, 1920, 590};
    }

    private static float fx(float x) {
        return FARM_X + 100 + (x - FARM_X - 100) * FARM_SCALE;
    }

    private static float fy(float y) {
        return FARM_BASE + (y - FARM_BASE) * FARM_SCALE;
    }

    @Override
    float lowSunY(float x) {
        return HORIZON + 4;
    }

    @Override
    boolean seasonalFog(Scene s) {
        // The plain's famous fog: on autumn dawns, and on winter dawns and nights.
        switch (s.season) {
            case AUTUMN:
                return s.phase == SceneView.Phase.DAWN;
            case WINTER:
            case CHRISTMAS:
                return s.phase == SceneView.Phase.DAWN || s.phase == SceneView.Phase.NIGHT;
            default:
                return false;
        }
    }

    /** The season's fields by daylight: three crops that take turns across the patchwork. */
    private static int[] crops(Season season) {
        switch (season) {
            case SPRING:
                return new int[]{0xFF8DB461, 0xFFCFC778, 0xFFA3C27E, 0xFF7FA65A};
            case SUMMER:
                return new int[]{0xFFD6BC72, 0xFF7C9A52, 0xFFC9B07E, 0xFFB8A464};
            case AUTUMN:
                return new int[]{0xFF8E715A, 0xFFA48C66, 0xFF84985E, 0xFF96785C};
            default:
                return new int[]{0xFFB9BFB0, 0xFFA3A898, 0xFFC4C6BA, 0xFFADB2A4};
        }
    }

    @Override
    void draw(Scene s, Canvas c) {
        Scene.Palette pal = s.pal;
        int alpsRock = s.hazed(s.lit(0xFF8E96A6), 0.7f);
        s.hill(c, alps, alpsRock, 570, 640);
        // The distant tree lines along the horizon.
        int treeLine = s.hazed(Scene.mix(pal.h3, 0xFF203020, 0.4f), 0.5f);
        Path trees = new Path();
        trees.addRect(0, HORIZON - 4, Scene.W, HORIZON + 8, Path.Direction.CW);
        for (int i = 0; i < 70; i++) {
            float x = i * 28 + 11 * (float) Math.sin(i * 1.9f);
            trees.addOval(x - 12, HORIZON - 10 - 4 * (float) Math.abs(Math.sin(i)), x + 12, HORIZON + 4, Path.Direction.CW);
        }
        s.fill(c, trees, treeLine);

        int[] crops = crops(s.season);
        boolean festive = s.season == Season.CHRISTMAS || s.season == Season.HALLOWEEN;
        for (int band = 0; band < BANDS.length - 1; band++) {
            float y0 = BANDS[band], y1 = BANDS[band + 1];
            float depth = 1f - (band / (float) (BANDS.length - 2));
            // Each band split into fields of uneven width, whose edges run to the vanishing point.
            float shift = (band * 437) % 700;
            float[] cuts = {-1600, -700 + shift * 0.6f, 80 + shift, 620 + shift * 0.4f, 1400 + shift * 0.7f, 2100 + shift * 0.3f, 3600};
            for (int k = 0; k < cuts.length - 1; k++) {
                int crop = crops[(band * 3 + k * 5 + (band * k) % 2) % crops.length];
                // Muted towards the land's own colour, so the patchwork stays calm.
                int color = festive ? Scene.mix(k % 2 == 0 ? pal.h1 : pal.h2, pal.h3, band / 12f)
                        : Scene.mix(s.lit(crop), pal.h1, 0.25f);
                color = s.hazed(color, 0.5f * depth * depth);
                Path field = new Path();
                field.moveTo(edge(cuts[k], y0), y0);
                field.lineTo(edge(cuts[k + 1], y0), y0);
                field.lineTo(edge(cuts[k + 1], y1), y1);
                field.lineTo(edge(cuts[k], y1), y1);
                field.close();
                s.fill(c, field, color);
                if (band >= 3 && (band + k) % 3 == 0) furrows(s, c, field, cuts[k], cuts[k + 1], y0, y1, color);
            }
        }

        // A ditch reflecting the sky, along the road to the farm.
        Path ditch = new Path();
        ditch.moveTo(980, 700);
        ditch.lineTo(1920, 684);
        s.stroke(c, ditch, Scene.alpha(Scene.mix(pal.s3, 0xFFFFFFFF, 0.2f), 0.55f), 3);

        int leaves = s.lit(s.foliage());
        if (s.season == Season.HALLOWEEN) leaves = 0xFF2A1A34;
        for (int i = 0; i < 20; i++) {
            float x = 120 + i * 34;
            s.poplar(c, x, 674 + (i % 3) * 0.7f, 74 + 6 * (float) Math.sin(i * 1.7f), leaves);
        }
        for (int i = 0; i < 9; i++) s.poplar(c, 1000 + i * 26, 656, 40, s.hazed(leaves, 0.35f));

        s.houseGlow(c, fx(FARM_X + 130), FARM_BASE - 30, 170);
        farm(s, c);
        s.poplar(c, 1660, 690, 96, leaves);
        s.poplar(c, 1694, 692, 84, leaves);
        guests(s, c, 1780, 694, 0.85f);
    }

    /** Where the edge through x at the bottom of the canvas crosses height y, on its way to the vanishing point. */
    private static float edge(float bottomX, float y) {
        return VX + (bottomX - VX) * (y - HORIZON + 30) / (Scene.H - HORIZON + 30);
    }

    /** Faint rows across a field, running to the vanishing point like its edges. */
    private void furrows(Scene s, Canvas c, Path field, float a, float b, float y0, float y1, int color) {
        c.save();
        c.clipPath(field);
        s.paint.setStrokeWidth(2);
        s.paint.setColor(Scene.alpha(Scene.mix(color, 0xFF000000, 0.25f), 0.35f));
        for (int i = 1; i < 12; i++) {
            float x = a + (b - a) * i / 12f;
            c.drawLine(edge(x, y0), y0, edge(x, y1), y1, s.paint);
        }
        c.restore();
    }

    /** A plastered farmhouse, a barn with an arched portico, a dovecote tower. */
    private void farm(Scene s, Canvas c) {
        int plaster = s.lit(0xFFDDBD8C), brick = s.lit(0xFFA65A3E), roof = s.lit(0xFFB0563A);
        if (s.season == Season.HALLOWEEN) {
            plaster = 0xFF2E2440;
            brick = 0xFF241A30;
            roof = 0xFF180F20;
        }
        float x = FARM_X, b = FARM_BASE;
        c.save();
        c.scale(FARM_SCALE, FARM_SCALE, x + 100, b);
        // The barn: brick piers and dark arches under a long roof.
        s.paint.setColor(brick);
        c.drawRect(x, b - 40, x + 72, b, s.paint);
        s.paint.setColor(Scene.mix(brick, 0xFF000000, 0.55f));
        for (int i = 0; i < 3; i++) {
            float ax = x + 6 + i * 22;
            c.drawRect(ax, b - 26, ax + 16, b, s.paint);
            c.drawCircle(ax + 8, b - 26, 8, s.paint);
        }
        s.roof(c, x - 6, b - 38, x + 78, b - 54, roof);
        // The house.
        s.paint.setColor(plaster);
        c.drawRect(x + 76, b - 48, x + 206, b, s.paint);
        s.roof(c, x + 70, b - 46, x + 212, b - 68, roof);
        s.paint.setColor(Scene.mix(plaster, 0xFF000000, 0.1f));
        c.drawRect(x + 144, b - 70, x + 156, b - 56, s.paint);
        for (int i = 0; i < 4; i++) {
            s.window(c, x + 88 + i * 30, b - 38, x + 98 + i * 30, b - 26);
            s.window(c, x + 88 + i * 30, b - 18, x + 98 + i * 30, b - 6);
        }
        // The dovecote tower.
        s.paint.setColor(plaster);
        c.drawRect(x + 210, b - 86, x + 240, b, s.paint);
        s.paint.setColor(Scene.mix(plaster, 0xFF000000, 0.2f));
        c.drawRect(x + 230, b - 86, x + 240, b, s.paint);
        s.roof(c, x + 205, b - 84, x + 245, b - 100, roof);
        s.window(c, x + 220, b - 72, x + 226, b - 62);
        c.restore();
    }
}
