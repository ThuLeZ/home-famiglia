package com.cosyhome.tv;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;

/**
 * One kind of landscape under the shared sky: what stands on the ground, and where the season's
 * guests (the Christmas fir, the pumpkins) and the per-frame touches (chimney smoke, fairy lights)
 * go. Coordinates are on the 1920x1080 design canvas before the lift.
 */
abstract class Land {

    /** The kinds the TV picks from, by what is around it. */
    enum Kind { HILLS, MOUNTAINS, SEA, PLAINS, LAKE }

    static Land of(Kind kind) {
        switch (kind) {
            case MOUNTAINS:
                return new MountainsLand();
            case SEA:
                return new SeaLand();
            case PLAINS:
                return new PlainsLand();
            case LAKE:
                return new LakeLand();
            default:
                return new HillsLand();
        }
    }

    final Kind kind;

    /** The chimney top the smoke rises from; none when x is negative. */
    float smokeX = -1, smokeY;
    /** The Christmas fir's foot and height. */
    float treeX, treeBase, treeH = 170;
    /** Pumpkins as {x, y, radius}, for the candle flicker too. */
    float[][] candles = {};
    /** The roofline the Christmas fairy lights hang along. */
    float eaveX0, eaveX1, eaveY;
    /** The sky's lower edge, where nothing on the ground is: the stars stop above it. */
    float[] skyline = {0, 600, Scene.W, 600};

    Land(Kind kind) {
        this.kind = kind;
    }

    /** All the ground: the canvas is already lifted, and the land must cover down to the bottom. */
    abstract void draw(Scene s, Canvas c);

    /** Per-frame touches of this land (water glints, boats, a lighthouse beam); lifted like {@link #draw}. */
    void frame(Scene s, Canvas c, Paint fx, float t) {
    }

    /** Whether this land lies under a bank of fog in this season and light, whatever the forecast. */
    boolean seasonalFog(Scene s) {
        return false;
    }

    /** Where the low sun sits at dawn and dusk at this x: just behind the skyline. */
    float lowSunY(float x) {
        return skylineAt(x) + 14;
    }

    /** The height of the skyline at x, between its points. */
    final float skylineAt(float x) {
        float[] k = skyline;
        if (x <= k[0]) return k[1];
        for (int i = 2; i < k.length; i += 2) {
            if (x <= k[i]) {
                float t = (x - k[i - 2]) / (k[i] - k[i - 2]);
                return k[i - 1] + (k[i + 1] - k[i - 1]) * t;
            }
        }
        return k[k.length - 1];
    }

    /** The Christmas fir, or the bare tree and pumpkins of Halloween, where this land puts them. */
    final void guests(Scene s, Canvas c, float bareX, float bareBase, float bareScale) {
        if (s.season == Season.CHRISTMAS) s.christmasTree(c, treeX, treeBase, treeH);
        if (s.season == Season.HALLOWEEN) {
            s.bareTree(c, bareX, bareBase, bareScale);
            for (int i = 0; i < candles.length; i++) s.pumpkin(c, candles[i][0], candles[i][1], candles[i][2], i);
        }
    }

    // ---- shared per-frame touches ---------------------------------------------------------------

    /** A small sailboat crossing slowly at height y, between x0 and x1; a light on its mast at night. */
    static void sailboat(Scene s, Canvas c, Paint fx, float t, float x0, float x1, float y, float size, float period) {
        float u = (t % period) / period;
        float x = x0 + (x1 - x0) * u;
        float bob = (float) Math.sin(t * 1.3f) * size * 0.04f;
        int hull = s.lit(0xFF5A4636), sail = s.lit(0xFFF4EFE4);
        Path p = new Path();
        p.moveTo(x - size * 0.5f, y + bob);
        p.lineTo(x + size * 0.5f, y + bob);
        p.lineTo(x + size * 0.36f, y + size * 0.16f + bob);
        p.lineTo(x - size * 0.36f, y + size * 0.16f + bob);
        p.close();
        fx.setColor(hull);
        c.drawPath(p, fx);
        Path s1 = new Path();
        s1.moveTo(x, y - size * 0.95f + bob);
        s1.lineTo(x + size * 0.38f, y - size * 0.06f + bob);
        s1.lineTo(x, y - size * 0.06f + bob);
        s1.close();
        Path s2 = new Path();
        s2.moveTo(x - size * 0.03f, y - size * 0.8f + bob);
        s2.lineTo(x - size * 0.03f, y - size * 0.06f + bob);
        s2.lineTo(x - size * 0.34f, y - size * 0.06f + bob);
        s2.close();
        fx.setColor(sail);
        c.drawPath(s1, fx);
        fx.setColor(Scene.mix(sail, 0xFF000000, 0.12f));
        c.drawPath(s2, fx);
        // Its reflection, broken by the water.
        fx.setColor(Scene.alpha(sail, 0.18f));
        for (int i = 0; i < 4; i++) {
            float yy = y + size * 0.22f + i * size * 0.12f + bob;
            float w = size * (0.3f - i * 0.06f);
            c.drawRect(x - w * 0.2f, yy, x + w, yy + size * 0.04f, fx);
        }
        if (s.glow() > 0) {
            fx.setColor(Scene.alpha(s.pal.win, 0.9f));
            c.drawCircle(x, y - size * 0.95f + bob, size * 0.03f + 1.2f, fx);
        }
    }

    /** Glints and slow ripples on water between y0 and y1, brighter in a column under the light at lx. */
    static void ripples(Scene s, Canvas c, Paint fx, float t, float y0, float y1, float lx, float lightW) {
        int n = 46;
        for (int i = 0; i < n; i++) {
            float depth = (i * 0.61803f) % 1f;                 // 0 far, 1 near
            float y = y0 + (y1 - y0) * depth * depth;
            float drift = (t * (6 + depth * 10) + i * 97) % (Scene.W + 200) - 100;
            float len = 10 + depth * 46;
            float near = Math.abs(drift - lx) < lightW * (0.6f + depth) ? 1f : 0f;
            float a = (0.05f + 0.06f * depth) + near * (0.25f + 0.25f * (float) Math.sin(t * 3 + i));
            fx.setColor(Color.argb(Math.round(Math.max(0, a) * 255), 255, 250, 235));
            c.drawRect(drift, y, drift + len, y + 1.2f + depth * 1.6f, fx);
        }
    }
}
