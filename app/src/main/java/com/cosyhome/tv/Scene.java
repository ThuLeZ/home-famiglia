package com.cosyhome.tv;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;

/**
 * What a landscape is drawn with: the moment (phase, season, weather, palette, lift) and the brushes
 * every landscape shares, from hills and trees to roofs and glows. All on the 1920x1080 design canvas.
 */
final class Scene {

    static final float W = 1920f, H = 1080f;

    /** Sky stops, three land colours from far to near, window colour, window glow, dark clock text. */
    static final class Palette {
        final int s1, s2, s3, h1, h2, h3, win;
        final float glow;
        final boolean darkText;

        Palette(int s1, int s2, int s3, int h1, int h2, int h3, int win, float glow, boolean darkText) {
            this.s1 = s1;
            this.s2 = s2;
            this.s3 = s3;
            this.h1 = h1;
            this.h2 = h2;
            this.h3 = h3;
            this.win = win;
            this.glow = glow;
            this.darkText = darkText;
        }

        Palette hills(int a, int b, int c) {
            return new Palette(s1, s2, s3, a, b, c, win, glow, darkText);
        }
    }

    SceneView.Phase phase = SceneView.Phase.NIGHT;
    Season season = Season.SPRING;
    Season.Fx sky = Season.Fx.CLEAR;
    Palette pal;
    /** How far the land sits above the bottom, to clear the rows of tiles. */
    float lift;

    /** For the still layers. Shaders draw with the paint's alpha, so set them through {@link #shader}. */
    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    boolean night() {
        return phase == SceneView.Phase.NIGHT;
    }

    boolean day() {
        return phase == SceneView.Phase.DAY;
    }

    boolean snowy() {
        return season == Season.CHRISTMAS || season == Season.WINTER;
    }

    /** How strongly lit windows glow: none by day, most at night. */
    float glow() {
        return pal.glow;
    }

    // ---- colour ---------------------------------------------------------------------------------

    static int mix(int a, int b, float t) {
        float u = 1 - t;
        return Color.argb(Math.round(Color.alpha(a) * u + Color.alpha(b) * t),
                Math.round(Color.red(a) * u + Color.red(b) * t),
                Math.round(Color.green(a) * u + Color.green(b) * t),
                Math.round(Color.blue(a) * u + Color.blue(b) * t));
    }

    static int alpha(int color, float a) {
        return (Math.round(Math.max(0, Math.min(1, a)) * 255) << 24) | (color & 0xFFFFFF);
    }

    /** A material's colour in the light of the phase: itself by day, warmer at dawn and dusk, blue at night. */
    int lit(int dayColor) {
        switch (phase) {
            case DAY:
                return dayColor;
            case DAWN:
                return mix(dayColor, 0xFF7E6A8E, 0.35f);
            case DUSK:
                return mix(dayColor, 0xFF8A4A58, 0.4f);
            default:
                return mix(dayColor, 0xFF1C2340, 0.75f);
        }
    }

    /** How far something at this depth (0 near, 1 the horizon) is veiled by the air. */
    int hazed(int color, float depth) {
        return mix(color, pal.s3, depth);
    }

    // ---- primitives -----------------------------------------------------------------------------

    /**
     * Sets a gradient on the still paint at full opacity: a shader draws with the paint's alpha, so a
     * translucent colour left from the step before would fade the whole fill.
     */
    void shader(Shader s) {
        paint.setColor(0xFF000000);
        paint.setShader(s);
    }

    void fill(Canvas c, Path p, int color) {
        paint.setColor(color);
        c.drawPath(p, paint);
    }

    static Path circle(float x, float y, float r) {
        Path p = new Path();
        p.addCircle(x, y, r, Path.Direction.CW);
        return p;
    }

    static Path oval(float x, float y, float rx, float ry) {
        Path p = new Path();
        p.addOval(x - rx, y - ry, x + rx, y + ry, Path.Direction.CW);
        return p;
    }

    /** A path through the points, closed down to the bottom of the canvas and beyond, for any lift. */
    static Path ridge(float... xy) {
        Path p = new Path();
        p.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) p.lineTo(xy[i], xy[i + 1]);
        p.lineTo(W, H + 400);
        p.lineTo(0, H + 400);
        p.close();
        return p;
    }

    /** A smooth path from cubic segments (x1 y1 x2 y2 x y after the start point), closed to the bottom. */
    static Path curve(float x0, float y0, float... cubics) {
        Path p = new Path();
        p.moveTo(x0, y0);
        for (int i = 0; i < cubics.length; i += 6) {
            p.cubicTo(cubics[i], cubics[i + 1], cubics[i + 2], cubics[i + 3], cubics[i + 4], cubics[i + 5]);
        }
        p.lineTo(W, H + 400);
        p.lineTo(0, H + 400);
        p.close();
        return p;
    }

    /**
     * A shape standing on a flat line, an island on the horizon: cubic segments from the start
     * point, closed straight back to it.
     */
    static Path mound(float x0, float y0, float... cubics) {
        Path p = new Path();
        p.moveTo(x0, y0);
        for (int i = 0; i < cubics.length; i += 6) {
            p.cubicTo(cubics[i], cubics[i + 1], cubics[i + 2], cubics[i + 3], cubics[i + 4], cubics[i + 5]);
        }
        p.close();
        return p;
    }

    /** A layer of land with a gentle top-to-bottom shade, lighter on the crest. */
    void hill(Canvas c, Path p, int color, float top, float bottom) {
        shader(new LinearGradient(0, top, 0, bottom, mix(color, 0xFFFFFFFF, 0.05f),
                mix(color, 0xFF000000, 0.14f), Shader.TileMode.CLAMP));
        c.drawPath(p, paint);
        paint.setShader(null);
    }

    /** Ploughed rows or vines: faint curves that follow the hill, clipped to it. */
    void rows(Canvas c, Path hill, int color, float strength) {
        c.save();
        c.clipPath(hill);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3);
        paint.setColor(alpha(color, strength));
        for (int i = 1; i <= 6; i++) {
            c.save();
            c.translate(0, i * 22 + i * i * 2);
            c.drawPath(hill, paint);
            c.restore();
        }
        paint.setStyle(Paint.Style.FILL);
        c.restore();
    }

    void stroke(Canvas c, Path p, int color, float width) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(width);
        paint.setColor(color);
        c.drawPath(p, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    // ---- light ----------------------------------------------------------------------------------

    /** A disc with a soft bloom, a touch lighter at its centre. */
    void glowDisc(Canvas c, float x, float y, float r, int disc, int glow, float glowAlpha) {
        glow(c, x, y, r, glow, glowAlpha);
        glow(c, x, y, r * 0.55f, glow, glowAlpha * 0.8f);
        shader(new RadialGradient(x - r * 0.25f, y - r * 0.25f, r * 1.2f, mix(disc, 0xFFFFFFFF, 0.5f), disc,
                Shader.TileMode.CLAMP));
        c.drawCircle(x, y, r, paint);
        paint.setShader(null);
    }

    /** The soft halo around a disc of radius r, without the disc itself. */
    void glow(Canvas c, float x, float y, float r, int color, float alpha) {
        shader(new RadialGradient(x, y, r * 2.8f, new int[]{alpha(color, alpha), alpha(color, alpha * 0.35f),
                color & 0xFFFFFF}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, r * 2.8f, paint);
        paint.setShader(null);
    }

    /** A wide, flat glow: an elliptical radial gradient. */
    void ellipseGlow(Canvas c, float x, float y, float rx, float ry, int color, float alpha) {
        RadialGradient g = new RadialGradient(0, 0, 1, alpha(color, alpha), color & 0xFFFFFF, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setScale(rx, ry);
        m.postTranslate(x, y);
        g.setLocalMatrix(m);
        shader(g);
        c.drawOval(x - rx, y - ry, x + rx, y + ry, paint);
        paint.setShader(null);
    }

    /** The warm pool of light a lit house throws on the land around it, after dark. */
    void houseGlow(Canvas c, float x, float y, float r) {
        if (pal.glow <= 0) return;
        int a = Math.round(pal.glow * 0x88);
        shader(new RadialGradient(x, y, r, (a << 24) | (pal.win & 0xFFFFFF), pal.win & 0xFFFFFF,
                Shader.TileMode.CLAMP));
        c.drawCircle(x, y, r, paint);
        paint.setShader(null);
    }

    /** A window: lit after dark with a small halo, dark by day. */
    void window(Canvas c, float l, float t, float r, float b) {
        paint.setColor(pal.glow > 0 ? pal.win : lit(0xFF3A4048));
        c.drawRect(l, t, r, b, paint);
        if (pal.glow > 0) glow(c, (l + r) / 2, (t + b) / 2, Math.max(r - l, b - t) * 0.5f, pal.win, 0.5f * pal.glow);
    }

    // ---- trees ----------------------------------------------------------------------------------

    /** A Tuscan cypress: a tall slim flame, rounded at the base and pointed at the top. */
    void cypress(Canvas c, float x, float base, float h, int color) {
        float w = h * 0.23f;
        // Its shadow on the grass, then the tree itself, tapering to a short trunk at the base.
        fill(c, oval(x + w * 0.3f, base, w * 0.8f, w * 0.14f), alpha(0xFF000000, 0.16f));
        Path p = new Path();
        p.moveTo(x - w * 0.08f, base);
        p.cubicTo(x - w * 0.7f, base - h * 0.08f, x - w * 0.55f, base - h * 0.55f, x - w * 0.12f, base - h * 0.92f);
        p.quadTo(x, base - h * 1.04f, x + w * 0.12f, base - h * 0.92f);
        p.cubicTo(x + w * 0.55f, base - h * 0.55f, x + w * 0.7f, base - h * 0.08f, x + w * 0.08f, base);
        p.close();
        shader(new LinearGradient(x - w * 0.5f, 0, x + w * 0.5f, 0, mix(color, 0xFFFFFFFF, 0.08f), color,
                Shader.TileMode.CLAMP));
        c.drawPath(p, paint);
        paint.setShader(null);
    }

    /** An umbrella pine: a leaning trunk and a wide, flat crown. {@code s} scales it. */
    void umbrellaPine(Canvas c, float x, float base, float s, int color) {
        c.save();
        c.scale(s, s, x, base);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(mix(color, 0xFF3A2A20, 0.4f));
        paint.setStrokeWidth(12);
        Path trunk = new Path();
        trunk.moveTo(x, base);
        trunk.cubicTo(x + 2, base - 60, x + 18, base - 100, x + 30, base - 132);
        c.drawPath(trunk, paint);
        paint.setStrokeWidth(6);
        c.drawLine(x + 20, base - 108, x - 14, base - 136, paint);
        c.drawLine(x + 28, base - 128, x + 64, base - 146, paint);
        paint.setStyle(Paint.Style.FILL);
        // The crown: a broad, flat-bottomed dome made of overlapping puffs.
        Path crown = new Path();
        crown.addOval(x - 96, base - 176, x + 40, base - 124, Path.Direction.CW);
        crown.addOval(x - 30, base - 204, x + 110, base - 134, Path.Direction.CW);
        crown.addOval(x + 40, base - 182, x + 152, base - 126, Path.Direction.CW);
        crown.addOval(x - 70, base - 198, x + 30, base - 144, Path.Direction.CW);
        crown.addOval(x - 112, base - 156, x - 30, base - 122, Path.Direction.CW);
        shader(new LinearGradient(0, base - 190, 0, base - 124, mix(color, 0xFFFFFFFF, 0.14f),
                mix(color, 0xFF000000, 0.1f), Shader.TileMode.CLAMP));
        c.drawPath(crown, paint);
        paint.setShader(null);
        c.restore();
    }

    /** A mountain fir: a narrow spire of three stacked tiers, snow on the tiers in winter. */
    void fir(Canvas c, float x, float base, float h, int color) {
        float w = h * 0.36f;
        Path p = new Path();
        p.moveTo(x, base - h);
        for (int i = 0; i < 3; i++) {
            float yb = base - h * 0.1f - (2 - i) * h * 0.27f, half = w * (0.42f + i * 0.29f) / 2;
            p.lineTo(x + half, yb);
            p.lineTo(x + half * 0.45f, yb - h * 0.04f);
        }
        p.lineTo(x + w * 0.08f, base - h * 0.1f);
        p.lineTo(x + w * 0.08f, base);
        p.lineTo(x - w * 0.08f, base);
        p.lineTo(x - w * 0.08f, base - h * 0.1f);
        for (int i = 2; i >= 0; i--) {
            float yb = base - h * 0.1f - (2 - i) * h * 0.27f, half = w * (0.42f + i * 0.29f) / 2;
            p.lineTo(x - half * 0.45f, yb - h * 0.04f);
            p.lineTo(x - half, yb);
        }
        p.close();
        fill(c, p, color);
        if (snowy()) {
            // A dusting on the tip and on each tier's shoulders.
            Path tip = new Path();
            tip.moveTo(x, base - h);
            tip.lineTo(x + w * 0.1f, base - h * 0.86f);
            tip.lineTo(x - w * 0.1f, base - h * 0.86f);
            tip.close();
            fill(c, tip, lit(0xFFF2F6FA));
        }
    }

    /**
     * A Lombard poplar: a tall, narrow column, gold in autumn, bare twigs in winter. Lines of them
     * edge the fields of the plain.
     */
    void poplar(Canvas c, float x, float base, float h, int leaves) {
        float w = h * 0.2f;
        int bark = lit(0xFF4E4A42);
        if (season == Season.WINTER || season == Season.CHRISTMAS) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(alpha(bark, 0.85f));
            paint.setStrokeWidth(Math.max(1.5f, h * 0.035f));
            c.drawLine(x, base, x, base - h, paint);
            paint.setStrokeWidth(Math.max(1f, h * 0.018f));
            for (int i = 1; i <= 5; i++) {
                float y = base - h * (0.25f + i * 0.13f);
                c.drawLine(x, y, x - w * 0.45f, y - h * 0.16f, paint);
                c.drawLine(x, y + h * 0.05f, x + w * 0.45f, y - h * 0.12f, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            return;
        }
        paint.setColor(bark);
        c.drawRect(x - w * 0.06f, base - h * 0.15f, x + w * 0.06f, base, paint);
        Path p = new Path();
        p.moveTo(x, base - h);
        p.cubicTo(x + w * 0.55f, base - h * 0.8f, x + w * 0.6f, base - h * 0.3f, x, base - h * 0.1f);
        p.cubicTo(x - w * 0.6f, base - h * 0.3f, x - w * 0.55f, base - h * 0.8f, x, base - h);
        shader(new LinearGradient(x - w * 0.5f, 0, x + w * 0.5f, 0, mix(leaves, 0xFFFFFFFF, 0.12f),
                mix(leaves, 0xFF000000, 0.12f), Shader.TileMode.CLAMP));
        c.drawPath(p, paint);
        paint.setShader(null);
    }

    /** The colour of broadleaf trees in the season, by daylight. */
    int foliage() {
        switch (season) {
            case AUTUMN:
                return 0xFFC9963A;
            case SPRING:
                return 0xFF7FAE5A;
            case HALLOWEEN:
                return 0xFF3A2440;
            default:
                return 0xFF5E8A46;
        }
    }

    // ---- buildings ------------------------------------------------------------------------------

    /** A low-pitched hip roof from (x0, eave) to (x1, eave) with its ridge at {@code ridge}. */
    void roof(Canvas c, float x0, float eave, float x1, float ridge, int color) {
        Path p = new Path();
        float inset = (x1 - x0) * 0.22f;
        p.moveTo(x0, eave);
        p.lineTo(x0 + inset, ridge);
        p.lineTo(x1 - inset, ridge);
        p.lineTo(x1, eave);
        p.close();
        shader(new LinearGradient(0, ridge, 0, eave, mix(color, 0xFFFFFFFF, 0.12f), color, Shader.TileMode.CLAMP));
        c.drawPath(p, paint);
        paint.setShader(null);
        if (season == Season.CHRISTMAS) snowCap(c, x0, eave, x1, ridge);
    }

    /** A steep gable seen from the front, from (x0, eave) to (x1, eave), its apex at {@code apex}. */
    void gable(Canvas c, float x0, float eave, float x1, float apex, int color) {
        Path p = new Path();
        p.moveTo(x0, eave);
        p.lineTo((x0 + x1) / 2, apex);
        p.lineTo(x1, eave);
        p.close();
        shader(new LinearGradient(x0, 0, x1, 0, mix(color, 0xFFFFFFFF, 0.1f), mix(color, 0xFF000000, 0.15f),
                Shader.TileMode.CLAMP));
        c.drawPath(p, paint);
        paint.setShader(null);
        if (season == Season.CHRISTMAS || season == Season.WINTER) {
            Path s = new Path();
            float mx = (x0 + x1) / 2, d = (eave - apex) * 0.35f;
            s.moveTo(x0 + 2, eave - 2);
            s.lineTo(mx, apex);
            s.lineTo(x1 - 2, eave - 2);
            s.lineTo(x1 - (x1 - mx) * 0.3f, eave - 2 - d * 0.3f);
            s.lineTo(mx, apex + d);
            s.lineTo(x0 + (mx - x0) * 0.3f, eave - 2 - d * 0.3f);
            s.close();
            fill(c, s, lit(0xFFF4F8FC));
        }
    }

    private void snowCap(Canvas c, float x0, float eave, float x1, float ridge) {
        Path p = new Path();
        float inset = (x1 - x0) * 0.22f, depth = (eave - ridge) * 0.45f;
        p.moveTo(x0 + 2, eave - depth * 0.4f);
        p.lineTo(x0 + inset, ridge);
        p.lineTo(x1 - inset, ridge);
        p.lineTo(x1 - 2, eave - depth * 0.4f);
        p.quadTo((x0 + x1) / 2, ridge + depth * 1.2f, x0 + 2, eave - depth * 0.4f);
        fill(c, p, lit(0xFFF4F8FC));
    }

    // ---- the season's guests --------------------------------------------------------------------

    /** A slim fir with scalloped tiers, baubles and a star: Christmas by the house. */
    void christmasTree(Canvas c, float x, float b, float h) {
        paint.setColor(0xFF4A2E1E);
        c.drawRect(x - h * 0.037f, b - h * 0.095f, x + h * 0.037f, b, paint);
        int green = night() ? 0xFF17452F : day() ? 0xFF2B6A48 : 0xFF1F5539;
        int tiers = 4;
        for (int i = 0; i < tiers; i++) {
            float top = b - h + i * h * 0.2f, bottom = b - h * 0.084f - (tiers - 1 - i) * h * 0.17f;
            float half = (bottom - (b - h)) * 0.38f;
            Path p = new Path();
            p.moveTo(x, top);
            p.quadTo(x + half * 0.45f, top + (bottom - top) * 0.55f, x + half, bottom);
            for (int k = 0; k < 3; k++) {
                float x0 = x + half - k * half * 2 / 3f, x1 = x0 - half * 2 / 3f;
                p.quadTo((x0 + x1) / 2, bottom - h * 0.037f, x1, bottom);
            }
            p.quadTo(x - half * 0.45f, top + (bottom - top) * 0.55f, x, top);
            p.close();
            shader(new LinearGradient(x - half, 0, x + half, 0, mix(green, 0xFFFFFFFF, 0.12f),
                    mix(green, 0xFF000000, 0.2f), Shader.TileMode.CLAMP));
            c.drawPath(p, paint);
            paint.setShader(null);
        }
        int[] baubles = {0xFFC8323F, 0xFFE6B84A, 0xFFC8323F, 0xFFE6B84A, 0xFFC8323F, 0xFFE6B84A};
        for (int i = 0; i < baubles.length; i++) {
            float t = (i + 1) / 7f;
            float by = b - h + h * 0.9f * t + 4, spread = (by - (b - h)) * 0.3f;
            fill(c, circle(x + (i % 2 == 0 ? -1 : 1) * spread * 0.6f, by, h * 0.024f), baubles[i]);
        }
        star(c, x, b - h - 4, h * 0.068f, 0xFFFFD86A);
    }

    private void star(Canvas c, float x, float y, float r, int color) {
        glow(c, x, y, r, color, 0.4f);
        Path p = new Path();
        for (int i = 0; i < 10; i++) {
            float rr = i % 2 == 0 ? r : r * 0.42f;
            double a = -Math.PI / 2 + i * Math.PI / 5;
            float px = x + rr * (float) Math.cos(a), py = y + rr * (float) Math.sin(a);
            if (i == 0) p.moveTo(px, py);
            else p.lineTo(px, py);
        }
        p.close();
        fill(c, p, color);
    }

    /** A bare old tree, more spooky than scary, standing at (x, base); {@code s} scales it. */
    void bareTree(Canvas c, float x, float base, float s) {
        c.save();
        c.scale(s, s, x, base);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(0xFF0A0512);
        paint.setStrokeWidth(22);
        c.drawLine(x, base, x + 6, base - 200, paint);
        paint.setStrokeWidth(11);
        c.drawLine(x + 4, base - 120, x - 80, base - 200, paint);
        c.drawLine(x + 6, base - 160, x + 70, base - 250, paint);
        paint.setStrokeWidth(6);
        c.drawLine(x - 50, base - 172, x - 104, base - 176, paint);
        c.drawLine(x - 64, base - 186, x - 68, base - 240, paint);
        c.drawLine(x + 48, base - 222, x + 10, base - 270, paint);
        paint.setStyle(Paint.Style.FILL);
        c.restore();
    }

    /** The pumpkin's ribs as {x offset, half width, shade}, all in units of its radius. */
    private static final float[][] LOBES = {{-0.62f, 0.5f, 0}, {0.62f, 0.5f, 0}, {-0.3f, 0.5f, 1}, {0.3f, 0.5f, 1}, {0f, 0.46f, 0}};

    /** A jack-o'-lantern with a round goofy face; each {@code face} pulls a different one. */
    void pumpkin(Canvas c, float x, float y, float r, int face) {
        fill(c, oval(x, y + r * 0.9f, r * 1.05f, r * 0.2f), 0x66000000);
        paint.setColor(0xFF4C6B2A);
        c.drawRect(x - r * 0.09f, y - r * 1.14f, x + r * 0.09f, y - r * 0.8f, paint);
        for (float[] lobe : LOBES) {
            fill(c, oval(x + lobe[0] * r, y, lobe[1] * r, r * 0.92f), lobe[2] == 0 ? 0xFFD9651A : 0xFFEB7A22);
        }
        int light = 0xFFFFD56A;
        tri(c, x - r * 0.40f, y - r * 0.08f, r * 0.20f, face == 1 ? -1 : 1, light);
        tri(c, x + r * 0.40f, y - r * 0.08f, r * 0.20f, face == 2 ? 1 : (face == 1 ? -1 : 1), light);
        tri(c, x, y + r * 0.12f, r * 0.10f, 1, light);
        Path grin = new Path();
        float gy = y + r * 0.38f, gw = r * 0.58f, gh = face == 2 ? r * 0.14f : r * 0.26f;
        grin.moveTo(x - gw, gy - gh * 0.3f);
        grin.quadTo(x, gy + gh * 0.5f, x + gw, gy - gh * 0.3f);
        grin.quadTo(x, gy + gh * 1.7f, x - gw, gy - gh * 0.3f);
        grin.close();
        fill(c, grin, light);
    }

    private void tri(Canvas c, float x, float y, float r, int up, int color) {
        Path p = new Path();
        p.moveTo(x, y - up * r);
        p.lineTo(x + r, y + up * r);
        p.lineTo(x - r, y + up * r);
        p.close();
        fill(c, p, color);
    }
}
