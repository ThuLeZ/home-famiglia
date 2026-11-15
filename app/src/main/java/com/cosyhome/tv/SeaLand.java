package com.cosyhome.tv;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;

/**
 * The sea: a flat horizon with an island in the haze, the sun or moon laying a path of light on the
 * water, a headland with umbrella pines, a white house and a lighthouse whose beam turns at night.
 */
final class SeaLand extends Land {

    private static final float HORIZON = 600;
    private static final float LIGHT_X = 1396, LIGHT_BASE = 622, LIGHT_H = 96;
    private static final float HOUSE_X = 1540, HOUSE_BASE = 590;

    private final Path island = Scene.mound(90, 601,
            160, 590, 230, 568, 300, 566, 380, 564, 450, 586, 540, 601);
    private final Path island2 = Scene.mound(620, 601,
            660, 596, 700, 590, 740, 592, 780, 594, 800, 598, 830, 601);
    private final Path headland = headland();
    private final Path cliff = cliff();
    private final Path beach = Scene.curve(0, 900,
            400, 870, 800, 880, 1100, 900, 1300, 912, 1600, 890, 1920, 896);

    SeaLand() {
        super(Kind.SEA);
        smokeX = HOUSE_X + 100;
        smokeY = HOUSE_BASE - 60;
        treeX = 1700;
        treeBase = 584;
        treeH = 140;
        candles = new float[][]{{1452, 616, 20}, {1494, 610, 28}, {1650, 590, 18}};
        eaveX0 = HOUSE_X - 4;
        eaveX1 = HOUSE_X + 124;
        eaveY = HOUSE_BASE - 46;
        skyline = new float[]{0, 600, 160, 590, 300, 566, 450, 586, 560, 600, 1180, 600, 1380, 610, 1500, 584,
                1700, 566, 1920, 552};
    }

    private static Path headland() {
        Path p = new Path();
        p.moveTo(1150, 790);
        p.cubicTo(1220, 704, 1290, 652, 1380, 626);
        p.cubicTo(1480, 598, 1600, 576, 1740, 566);
        p.lineTo(1920, 552);
        p.lineTo(1920, Scene.H + 400);
        p.lineTo(1150, Scene.H + 400);
        p.close();
        return p;
    }

    /** The pale limestone face of the headland, where it drops to the sea. */
    private static Path cliff() {
        Path p = new Path();
        p.moveTo(1150, 790);
        p.cubicTo(1220, 704, 1290, 652, 1380, 626);
        p.lineTo(1396, 640);
        p.cubicTo(1340, 668, 1290, 720, 1262, 790);
        p.close();
        return p;
    }

    /** Where the light on the water comes from: the low sun, the high sun, or the moon. */
    private static float lightX(Scene s) {
        switch (s.phase) {
            case DAWN:
                return 1180;
            case DUSK:
                return 760;
            default:
                return 1500;
        }
    }

    @Override
    float lowSunY(float x) {
        return HORIZON + 8;
    }

    @Override
    void draw(Scene s, Canvas c) {
        Scene.Palette pal = s.pal;
        int deep;
        switch (s.phase) {
            case DAY:
                deep = 0xFF2F6E98;
                break;
            case DAWN:
                deep = 0xFF4E5684;
                break;
            case DUSK:
                deep = 0xFF4A3660;
                break;
            default:
                deep = 0xFF0B1330;
        }
        if (s.season == Season.HALLOWEEN) deep = 0xFF140A26;
        // The sea, catching the sky's colour towards the horizon.
        s.shader(new LinearGradient(0, HORIZON, 0, 1000, new int[]{Scene.mix(deep, pal.s3, 0.5f), deep,
                Scene.mix(deep, 0xFF000000, 0.25f)}, new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, HORIZON, Scene.W, Scene.H + 400, s.paint);
        s.paint.setShader(null);

        boolean clear = s.sky != Season.Fx.RAIN && s.sky != Season.Fx.FOG;
        if (clear && s.season != Season.HALLOWEEN) {
            // The path of light under the sun or moon.
            float x = lightX(s);
            int light = s.night() ? 0xFFE8E4D6 : s.day() ? 0xFFFFF6DA : 0xFFFFC890;
            float a = s.night() ? 0.22f : s.day() ? 0.3f : 0.5f;
            s.ellipseGlow(c, x, HORIZON + 140, s.day() ? 140 : 90, 230, light, a);
        }

        int far = s.hazed(pal.h2, 0.62f);
        s.hill(c, island, far, 560, 610);
        s.hill(c, island2, s.hazed(pal.h2, 0.72f), 588, 604);

        // The headland: scrub on top, a limestone cliff where it meets the water.
        int scrub = Scene.mix(pal.h2, 0xFF3E5A34, s.snowy() || s.season == Season.HALLOWEEN ? 0.15f : 0.35f);
        s.hill(c, headland, scrub, 560, 820);
        s.shader(new LinearGradient(1150, 0, 1396, 0, s.lit(0xFFC9BBA0), s.lit(0xFFE6DCC6), Shader.TileMode.CLAMP));
        c.drawPath(cliff, s.paint);
        s.paint.setShader(null);
        // Foam where the cliff meets the sea.
        Path foam = new Path();
        foam.moveTo(1150, 792);
        foam.cubicTo(1190, 788, 1230, 794, 1268, 790);
        s.stroke(c, foam, Scene.alpha(0xFFFFFFFF, s.night() ? 0.2f : 0.55f), 3);

        int pine = Scene.mix(pal.h3, 0xFF1A2C1C, s.night() ? 0.3f : 0.5f);
        s.houseGlow(c, HOUSE_X + 60, HOUSE_BASE - 24, 150);
        house(s, c);
        lighthouse(s, c);
        s.umbrellaPine(c, 1490, 606, 0.62f, pine);
        s.umbrellaPine(c, 1760, 572, 0.78f, pine);
        s.umbrellaPine(c, 1860, 562, 0.55f, pine);
        guests(s, c, 1880, 560, 0.6f);
        s.hill(c, beach, s.lit(s.season == Season.CHRISTMAS ? 0xFFEDEFF2 : 0xFFE3D2AE), 880, 1080);
    }

    /** A white house with a flat roof and blue shutters. */
    private void house(Scene s, Canvas c) {
        float x = HOUSE_X, b = HOUSE_BASE;
        int white = s.lit(0xFFF4EFE6);
        if (s.season == Season.HALLOWEEN) white = 0xFF3A3048;
        int shade = Scene.mix(white, 0xFF000000, 0.15f);
        s.paint.setColor(white);
        c.drawRect(x, b - 46, x + 84, b, s.paint);
        s.paint.setColor(shade);
        c.drawRect(x + 84, b - 34, x + 120, b, s.paint);
        s.paint.setColor(white);
        c.drawRect(x - 2, b - 50, x + 86, b - 46, s.paint);
        c.drawRect(x + 96, b - 64, x + 106, b - 46, s.paint);   // the chimney
        int shutter = s.lit(0xFF3F6E9A);
        for (int i = 0; i < 3; i++) {
            float wx = x + 12 + i * 24;
            s.window(c, wx, b - 36, wx + 10, b - 22);
            s.paint.setColor(shutter);
            c.drawRect(wx - 4, b - 36, wx - 1, b - 22, s.paint);
            c.drawRect(wx + 11, b - 36, wx + 14, b - 22, s.paint);
        }
        s.paint.setColor(Scene.mix(shade, 0xFF000000, 0.3f));
        c.drawRect(x + 98, b - 22, x + 110, b, s.paint);
    }

    /** A white tower with red bands, a gallery and a glass lantern. */
    private void lighthouse(Scene s, Canvas c) {
        float x = LIGHT_X, b = LIGHT_BASE, h = LIGHT_H;
        int white = s.lit(0xFFF6F2EA), red = s.lit(0xFFB8403A);
        if (s.season == Season.HALLOWEEN) {
            white = 0xFF3A3048;
            red = 0xFF22182E;
        }
        Path tower = new Path();
        tower.moveTo(x - 13, b);
        tower.lineTo(x - 8, b - h);
        tower.lineTo(x + 8, b - h);
        tower.lineTo(x + 13, b);
        tower.close();
        s.shader(new LinearGradient(x - 13, 0, x + 13, 0, white, Scene.mix(white, 0xFF000000, 0.18f),
                Shader.TileMode.CLAMP));
        c.drawPath(tower, s.paint);
        s.paint.setShader(null);
        s.paint.setColor(red);
        for (int i = 0; i < 2; i++) {
            float y0 = b - h * (0.3f + i * 0.34f), y1 = y0 - h * 0.12f;
            float w0 = 13 - 5 * (b - y0) / h, w1 = 13 - 5 * (b - y1) / h;
            Path band = new Path();
            band.moveTo(x - w0, y0);
            band.lineTo(x - w1, y1);
            band.lineTo(x + w1, y1);
            band.lineTo(x + w0, y0);
            band.close();
            c.drawPath(band, s.paint);
        }
        s.paint.setColor(Scene.mix(white, 0xFF000000, 0.35f));
        c.drawRect(x - 12, b - h - 3, x + 12, b - h, s.paint);
        boolean lit = s.glow() > 0;
        s.paint.setColor(lit ? 0xFFFFF0C0 : s.lit(0xFF9EB8C4));
        c.drawRect(x - 6, b - h - 15, x + 6, b - h - 3, s.paint);
        Path cap = new Path();
        cap.moveTo(x - 8, b - h - 15);
        cap.lineTo(x, b - h - 25);
        cap.lineTo(x + 8, b - h - 15);
        cap.close();
        s.fill(c, cap, red);
        if (lit) s.glow(c, x, b - h - 9, 10, 0xFFFFE8A8, 0.7f);
    }

    @Override
    void frame(Scene s, Canvas c, Paint fx, float t) {
        boolean clear = s.sky != Season.Fx.RAIN && s.sky != Season.Fx.FOG && s.season != Season.HALLOWEEN;
        ripples(s, c, fx, t, HORIZON + 4, 900, clear ? lightX(s) : -9999, s.day() ? 150 : 90);
        sailboat(s, c, fx, t, -120, 2040, 652, 46, 260);
        if (s.glow() > 0) beam(s, c, fx, t);
    }

    /** The lighthouse beam sweeping round: wide and bright when it points our way. */
    private void beam(Scene s, Canvas c, Paint fx, float t) {
        float x = LIGHT_X, y = LIGHT_BASE - LIGHT_H - 9;
        double a = (t * 0.6) % (2 * Math.PI);
        float dir = (float) Math.cos(a), toward = (float) Math.max(0, Math.sin(a));
        float len = 900 * Math.abs(dir) + 60;
        float spread = 14 + 30 * toward;
        float ex = x + dir * len;
        Path p = new Path();
        p.moveTo(x, y);
        p.lineTo(ex, y - spread);
        p.lineTo(ex, y + spread);
        p.close();
        fx.setColor(0xFF000000);   // full opacity: the gradients carry the fade
        fx.setShader(new LinearGradient(x, y, ex, y, Scene.alpha(0xFFFFF0C0, 0.32f + 0.3f * toward),
                Scene.alpha(0xFFFFF0C0, 0f), Shader.TileMode.CLAMP));
        c.drawPath(p, fx);
        fx.setShader(new RadialGradient(x, y, 30 + 40 * toward, Scene.alpha(0xFFFFF0C0, 0.4f + 0.5f * toward),
                Scene.alpha(0xFFFFF0C0, 0f), Shader.TileMode.CLAMP));
        c.drawCircle(x, y, 30 + 40 * toward, fx);
        fx.setShader(null);
    }
}
