package com.cosyhome.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/**
 * The landscape behind the clock: a sky that follows the sun, the seasons and the weather, over the
 * land around the TV ({@link Land}: hills, mountains, sea, plain or lake).
 *
 * Drawn on a 1920x1080 design canvas and scaled to the view. Everything that never moves is painted
 * once per phase into {@link #still}; each frame only adds the stars, clouds, smoke, water and weather
 * on top, at about 25 fps, so the TV's GPU is mostly drawing one bitmap.
 */
public class SceneView extends View {

    enum Phase { DAWN, DAY, DUSK, NIGHT }

    interface Listener {
        /** A new still image (and blurred copy) is ready: the phase or the size changed. */
        void onSceneChanged();
    }

    private static final float W = Scene.W, H = Scene.H;
    private static final long FRAME_MS = 40;

    private static final Scene.Palette DAWN = new Scene.Palette(0xFF323F6E, 0xFFB88AA6, 0xFFF2CBA8,
            0xFF9384A6, 0xFF655A88, 0xFF3C355C, 0xFFFFCB8C, 0.5f, false);
    private static final Scene.Palette DAY = new Scene.Palette(0xFF5B9DCB, 0xFFA3CCE6, 0xFFE6F1F2,
            0xFFA9C98E, 0xFF7FAE73, 0xFF4F8257, 0xFF3A4048, 0f, true);
    private static final Scene.Palette DUSK = new Scene.Palette(0xFF2B2650, 0xFFA45B7A, 0xFFF1AA6C,
            0xFF8C5769, 0xFF5B3456, 0xFF2D1D37, 0xFFFFBE6E, 0.75f, false);
    private static final Scene.Palette NIGHT = new Scene.Palette(0xFF060914, 0xFF0F1835, 0xFF202D55,
            0xFF1F2946, 0xFF161D35, 0xFF0D1224, 0xFFFFBE6E, 1f, false);

    /** Land colours of each season by daylight, far to near. */
    private static final int[][] SEASON_HILLS = {
            /* CHRISTMAS */ {0xFFF0F5F9, 0xFFDCE6EE, 0xFFC3D2DE},
            /* HALLOWEEN */ {0xFF3A1E46, 0xFF261433, 0xFF120A1C},
            /* SPRING    */ {0xFFB3D08F, 0xFF86B46F, 0xFF55875A},
            /* SUMMER    */ {0xFFD6C983, 0xFFADAE62, 0xFF718B47},
            /* AUTUMN    */ {0xFFD9B072, 0xFFB57E47, 0xFF7B4F31},
            /* WINTER    */ {0xFFBAC7CA, 0xFF93A4A9, 0xFF677980},
    };

    /** Stars as {x, y, radius, bright}: fixed, so the sky looks the same every night. */
    private static final float[][] STARS = new float[70][];
    /** Cloud drift lanes as {start x, y, sprite, speed px/s}. */
    private static final float[][] CLOUDS = {
            {200, 150, 0, 6}, {900, 240, 1, 4.5f}, {1500, 110, 2, 5.5f}, {1250, 290, 1, 3.5f}, {-200, 210, 2, 4}};

    static {
        Random r = new Random(7);
        for (int i = 0; i < STARS.length; i++) {
            float y = (float) Math.pow(r.nextFloat(), 1.4) * 560;   // denser high up, thinning at the horizon
            STARS[i] = new float[]{r.nextFloat() * W, y, 0.9f + r.nextFloat() * 1.5f, r.nextFloat() < 0.12f ? 1 : 0};
        }
    }

    private final Scene scene = new Scene();
    private Land land = Land.of(Land.Kind.HILLS);
    private Bitmap still;
    private Bitmap blurred;
    /** Soft cloud shapes, drawn once per still and moved each frame. Null when the sky has none. */
    private Bitmap[] clouds;
    private Listener listener;
    private boolean running;
    private final long start = SystemClock.uptimeMillis();
    /** For the per-frame touches, whose colours carry their own fades. */
    private final Paint fx = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint rain = new Paint(Paint.ANTI_ALIAS_FLAG);

    public SceneView(Context context, AttributeSet attrs) {
        super(context, attrs);
        scene.pal = NIGHT;
    }

    void setListener(Listener l) {
        listener = l;
    }

    Phase phase() {
        return scene.phase;
    }

    Land.Kind land() {
        return land.kind;
    }

    /** Daytime has a light sky, so the clock switches to dark text; rain dims it again. */
    boolean wantsDarkText() {
        return scene.pal.darkText && scene.sky != Season.Fx.RAIN;
    }

    /** A small, heavily blurred copy of the still scene, for the glass panels to sample. */
    Bitmap blurred() {
        return blurred;
    }

    /** Halloween is always after dark. Redraws the still only when something in it changed. */
    void update(Phase p, Season s, Season.Fx f) {
        if (s == Season.HALLOWEEN && p != Phase.DUSK) p = Phase.NIGHT;
        if (p == scene.phase && s == scene.season && f == scene.sky && still != null) return;
        scene.phase = p;
        scene.season = s;
        scene.sky = f;
        scene.pal = palette(p, s);
        rebuild();
    }

    /** The land around the TV; redraws only when it changes. */
    void setLand(Land.Kind kind) {
        if (kind == land.kind) return;
        land = Land.of(kind);
        if (still != null) rebuild();
    }

    void setLift(float designPixels) {
        if (designPixels == scene.lift) return;
        scene.lift = designPixels;
        if (still != null) rebuild();
    }

    private static Scene.Palette palette(Phase p, Season s) {
        Scene.Palette b = p == Phase.DAWN ? DAWN : p == Phase.DAY ? DAY : p == Phase.DUSK ? DUSK : NIGHT;
        int[] h = SEASON_HILLS[s.ordinal()];
        switch (s) {
            case HALLOWEEN:
                return p == Phase.DUSK
                        ? new Scene.Palette(0xFF1C0E3A, 0xFF6A2358, 0xFFE8722A, 0xFF4A2650, 0xFF2E1838, 0xFF160C20, 0xFFFFB347, 1f, false)
                        : new Scene.Palette(0xFF0C0720, 0xFF27104A, 0xFF6E2C40, 0xFF2C173E, 0xFF1C0F2C, 0xFF0E0718, 0xFFFFB347, 1f, false);
            case CHRISTMAS:
                if (p == Phase.DAY) return b.hills(h[0], h[1], h[2]);
                if (p == Phase.NIGHT) return b.hills(0xFF4A5880, 0xFF38446A, 0xFF283253);
                return b.hills(0xFFCFC8DC, 0xFFAAA2C2, 0xFF7E789C);
            default:
                if (p == Phase.NIGHT) return b;
                if (p == Phase.DAY) return b.hills(h[0], h[1], h[2]);
                // Dawn and dusk tint the season's colours with their own light.
                return b.hills(Scene.mix(h[0], b.h1, 0.55f), Scene.mix(h[1], b.h2, 0.55f), Scene.mix(h[2], b.h3, 0.55f));
        }
    }

    /** Frees the full-screen still while an app is in front; the next {@link #update} redraws it. */
    void release() {
        still = null;
        clouds = null;
    }

    /** Animate only while Home is on screen. */
    void setRunning(boolean run) {
        running = run;
        if (run) invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rebuild();
    }

    private void rebuild() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        // Full colour (about 8 MB at 1080p): RGB_565 bands visibly in the dark sky and the moon's glow.
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.scale(w / W, h / H);
        drawStill(c);
        still = bmp;
        clouds = scene.night() || scene.sky == Season.Fx.RAIN ? null : cloudSprites(w / W);
        blurred = Blur.of(bmp, 8, 5);
        invalidate();
        if (listener != null) listener.onSceneChanged();
    }

    // ---- the still layers -------------------------------------------------------------------------

    private void drawStill(Canvas c) {
        Scene s = scene;
        Scene.Palette pal = s.pal;
        Paint paint = s.paint;
        float lift = s.lift;
        // Sky: deep at the top, light at the horizon.
        s.shader(new LinearGradient(0, 0, 0, 700 - lift, new int[]{pal.s1, pal.s2, pal.s3},
                new float[]{0f, 0.62f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, paint);
        paint.setShader(null);

        boolean overcast = s.sky == Season.Fx.RAIN && s.season != Season.HALLOWEEN;
        c.save();
        c.translate(0, -lift);
        // The low sun warms a wide stretch of sky along the horizon.
        if (!overcast && (s.phase == Phase.DAWN || s.phase == Phase.DUSK)) {
            float x = s.phase == Phase.DAWN ? 1180 : 760;
            s.ellipseGlow(c, x, land.lowSunY(x) + 8, 820, 260, s.phase == Phase.DAWN ? 0xFFFFD9B0 : 0xFFFFB878, 0.55f);
        }
        if (!overcast) drawSunOrMoon(c);
        c.restore();

        if (s.season == Season.HALLOWEEN) {
            // A big low moon, with a soft halo and a couple of craters.
            s.glowDisc(c, 1420, 280, 140, 0xFFF4C766, 0xFFF0A040, 0.32f);
            s.fill(c, Scene.circle(1365, 232, 22), 0x33B87A2A);
            s.fill(c, Scene.circle(1462, 322, 32), 0x2EB87A2A);
            s.fill(c, Scene.circle(1480, 214, 13), 0x2EB87A2A);
        }

        c.save();
        c.translate(0, -lift);
        land.draw(s, c);
        if (s.sky == Season.Fx.FOG || land.seasonalFog(s)) drawFog(c);
        c.restore();

        if (overcast) {
            // Overcast: the whole picture dims, with a heavy, soft cloud bank along the top.
            paint.setColor(0x5E1E2834);
            c.drawRect(0, 0, W, H, paint);
            paint.setMaskFilter(new BlurMaskFilter(40, BlurMaskFilter.Blur.NORMAL));
            paint.setColor(0xD04C5562);
            c.drawOval(-200, -160, 900, 200, paint);
            c.drawOval(600, -180, 1500, 170, paint);
            c.drawOval(1200, -150, 2150, 210, paint);
            paint.setColor(0xB05A6370);
            c.drawOval(300, 60, 1100, 260, paint);
            c.drawOval(1100, 40, 1900, 250, paint);
            paint.setMaskFilter(null);
        }
    }

    private void drawSunOrMoon(Canvas c) {
        Scene s = scene;
        float lift = s.lift;
        switch (s.phase) {
            case DAWN:
                s.glowDisc(c, 1180, land.lowSunY(1180), 60, 0xFFFFE6C4, 0xFFFFD39C, 0.5f);
                break;
            case DAY:
                s.glowDisc(c, 1500, 210 + lift, 54, 0xFFFFFBEC, 0xFFFFF4D2, 0.5f);
                break;
            case DUSK:
                s.glowDisc(c, 760, land.lowSunY(760), 70, 0xFFFFD29A, 0xFFFFB46B, 0.55f);
                break;
            case NIGHT:
                if (s.season == Season.HALLOWEEN) break;
                s.glow(c, 1500, 210 + lift, 46, 0xFFE8E4D6, 0.18f);
                // The crescent: the moon's disc less an offset shadow disc, so the unlit part never
                // reaches past the moon's edge.
                Path moon = Scene.circle(1500, 210 + lift, 46);
                moon.op(Scene.circle(1520, 197 + lift, 41), Path.Op.DIFFERENCE);
                s.shader(new LinearGradient(1454, 0, 1546, 0, 0xFFF6F0DE, 0xFFD8D0BC, Shader.TileMode.CLAMP));
                c.drawPath(moon, s.paint);
                s.paint.setShader(null);
                break;
        }
    }

    /** Banks of fog lying over the land, thickest low down, the far land lost in them. */
    private void drawFog(Canvas c) {
        Scene s = scene;
        int fog = Scene.mix(s.pal.s3, 0xFFFFFFFF, s.night() ? 0.05f : 0.35f);
        if (s.night()) fog = Scene.mix(fog, 0xFF8090B0, 0.3f);
        s.shader(new LinearGradient(0, 470, 0, 760, new int[]{Scene.alpha(fog, 0f), Scene.alpha(fog, 0.62f),
                Scene.alpha(fog, 0.4f)}, new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 470, W, H + 400, s.paint);
        s.paint.setShader(null);
        s.paint.setMaskFilter(new BlurMaskFilter(30, BlurMaskFilter.Blur.NORMAL));
        s.paint.setColor(Scene.alpha(fog, 0.5f));
        c.drawOval(-200, 600, 900, 680, s.paint);
        c.drawOval(700, 620, 1700, 700, s.paint);
        c.drawOval(1300, 590, 2200, 660, s.paint);
        s.paint.setMaskFilter(null);
    }

    // ---- clouds -----------------------------------------------------------------------------------

    /** Three soft clouds at screen resolution, coloured by the light of the phase. */
    private Bitmap[] cloudSprites(float scale) {
        Phase phase = scene.phase;
        int top = phase == Phase.DAY ? 0xFFFFFFFF : phase == Phase.DAWN ? 0xFFFFE9E0 : 0xFFFFD6C2;
        int bottom = phase == Phase.DAY ? 0xFFDCE8F0 : phase == Phase.DAWN ? 0xFFD9A9B6 : 0xFFC98394;
        float[][][] shapes = {
                {{140, 110, 70}, {220, 90, 90}, {320, 105, 72}, {400, 125, 48}, {80, 130, 44}},
                {{110, 120, 50}, {190, 100, 66}, {270, 115, 52}},
                {{120, 118, 56}, {210, 92, 80}, {300, 108, 64}, {380, 126, 40}},
        };
        Bitmap[] out = new Bitmap[shapes.length];
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setMaskFilter(new BlurMaskFilter(10 * scale, BlurMaskFilter.Blur.NORMAL));
        for (int i = 0; i < shapes.length; i++) {
            int w = Math.round(500 * scale), h = Math.round(200 * scale);
            Bitmap b = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.scale(scale, scale);
            p.setShader(new LinearGradient(0, 30, 0, 160, top, bottom, Shader.TileMode.CLAMP));
            Path cloud = new Path();
            for (float[] puff : shapes[i]) cloud.addCircle(puff[0], puff[1], puff[2], Path.Direction.CW);
            // A broad, gently rounded underside, as fair-weather clouds have.
            float[] first = shapes[i][0], last = shapes[i][shapes[i].length - 1];
            cloud.addOval(first[0] - first[2] * 0.6f, 118, last[0] + last[2] * 0.6f, 158, Path.Direction.CW);
            Path below = new Path();
            below.addRect(0, 146, 500, 200, Path.Direction.CW);
            Path soft = new Path();
            soft.addOval(first[0] - first[2] * 0.6f, 118, last[0] + last[2] * 0.6f, 158, Path.Direction.CW);
            below.op(soft, Path.Op.DIFFERENCE);
            cloud.op(below, Path.Op.DIFFERENCE);
            c.drawPath(cloud, p);
            out[i] = b;
        }
        return out;
    }

    // ---- each frame -------------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        if (still == null) return;
        canvas.drawBitmap(still, 0, 0, null);
        Scene s = scene;
        float seconds = (SystemClock.uptimeMillis() - start) / 1000f;
        float sx = getWidth() / W;
        canvas.save();
        canvas.scale(sx, getHeight() / H);
        boolean overcast = s.sky == Season.Fx.RAIN;
        if (!overcast && (s.night() || s.phase == Phase.DUSK)) drawStars(canvas, seconds, s.night() ? 1f : 0.3f);
        if (clouds != null) drawClouds(canvas, seconds, sx);
        canvas.save();
        canvas.translate(0, -s.lift);
        land.frame(s, canvas, fx, seconds);
        if (s.phase != Phase.DAY && land.smokeX >= 0) drawSmoke(canvas, seconds);
        if (s.season == Season.CHRISTMAS) drawLights(canvas, seconds);
        if (s.season == Season.HALLOWEEN) drawCandles(canvas, seconds);
        canvas.restore();
        if (s.season == Season.HALLOWEEN) drawBats(canvas, seconds);
        if (s.sky == Season.Fx.RAIN) drawRain(canvas, seconds);
        else if (s.sky == Season.Fx.SNOW) drawSnow(canvas, seconds);
        canvas.restore();
        if (running) postInvalidateDelayed(FRAME_MS);
    }

    private void drawStars(Canvas c, float s, float strength) {
        float lift = scene.lift;
        for (int i = 0; i < STARS.length; i++) {
            float[] st = STARS[i];
            // Only in open sky: none in front of a mountain.
            float horizon = land.skylineAt(st[0]) - lift - 10;
            if (st[1] > horizon) continue;
            // Fainter towards the horizon; every other star twinkles on its own slow period.
            float fade = 1f - 0.7f * (st[1] / Math.max(1, 600 - lift));
            float twinkle = i % 2 == 0 ? 1f : 0.6f + 0.4f * (float) Math.sin(s * (0.7f + (i % 7) * 0.13f) + i);
            int a = Math.round(235 * strength * Math.max(0.2f, fade) * twinkle);
            fx.setColor(Color.argb(a, 255, 250, 240));
            c.drawCircle(st[0], st[1], st[2], fx);
            if (st[3] > 0) {
                // The brightest few get a faint cross of light.
                fx.setStrokeWidth(1.2f);
                fx.setColor(Color.argb(a / 3, 255, 250, 240));
                c.drawLine(st[0] - 9, st[1], st[0] + 9, st[1], fx);
                c.drawLine(st[0], st[1] - 9, st[0], st[1] + 9, fx);
            }
        }
    }

    private void drawClouds(Canvas c, float s, float scale) {
        Bitmap[] sprites = clouds;
        if (sprites == null) return;
        fx.setColor(Color.argb(scene.day() ? 220 : 170, 255, 255, 255));
        for (float[] cl : CLOUDS) {
            Bitmap b = sprites[(int) cl[2]];
            float span = W + 700;
            float x = ((cl[0] + s * cl[3]) % span + span) % span - 500;
            c.save();
            c.translate(x, cl[1] - 100);
            c.scale(1 / scale, 1 / scale);
            c.drawBitmap(b, 0, 0, fx);
            c.restore();
        }
    }

    private void drawSmoke(Canvas c, float s) {
        for (int i = 0; i < 4; i++) {
            float t = ((s + i * 2.2f) % 8.8f) / 8.8f;             // 0..1 over a slow rise
            float alpha = t < 0.15f ? t / 0.15f * 0.35f : 0.35f * (1 - (t - 0.15f) / 0.85f);
            float r = 6 * (0.6f + 1.6f * t);
            fx.setColor(Color.argb(Math.round(alpha * 255), 0xDC, 0xD8, 0xE4));
            c.drawCircle(land.smokeX + 26 * t + 6 * (float) Math.sin(t * 6), land.smokeY - 6 - 110 * t, r, fx);
        }
    }

    /** Warm fairy lights: garlands round the fir, and along the eaves. */
    private void drawLights(Canvas c, float s) {
        float x = land.treeX, b = land.treeBase, h = land.treeH;
        int n = 0;
        for (int row = 0; row < 3; row++) {
            float y0 = b - h + h * (0.23f + row * 0.25f), half = (y0 - (b - h)) * 0.36f;
            for (int k = 0; k <= 4; k++, n++) {
                float t = k / 4f;
                float px = x - half + 2 * half * t, py = y0 + h * 0.05f * t - h * 0.02f * (float) Math.sin(t * Math.PI);
                light(c, px, py, s, n);
            }
        }
        for (int k = 0; k < 8; k++, n++) {
            light(c, land.eaveX0 + (land.eaveX1 - land.eaveX0) * k / 7f, land.eaveY, s, n);
        }
    }

    private void light(Canvas c, float x, float y, float s, int i) {
        float on = 0.55f + 0.45f * (float) Math.sin(s * (1.1f + (i % 5) * 0.23f) + i * 1.7f);
        int color = i % 3 == 0 ? 0xFFFFE6A0 : 0xFFFFD27A;
        fx.setColor(Scene.alpha(color, 0.25f * on));
        c.drawCircle(x, y, 7, fx);
        fx.setColor(Scene.alpha(color, 0.6f + 0.4f * on));
        c.drawCircle(x, y, 2.4f, fx);
    }

    private void drawCandles(Canvas c, float s) {
        float[][] candles = land.candles;
        for (int i = 0; i < candles.length; i++) {
            float[] p = candles[i];
            float flicker = 0.08f + 0.07f * (0.5f + 0.5f * (float) Math.sin(s * 7f + i * 2f + Math.sin(s * 2.3f) * 3f));
            fx.setColor((Math.round(flicker * 255) << 24) | 0xFFD070);
            c.drawCircle(p[0], p[1], p[2] * 1.02f, fx);
        }
    }

    /** Three small bats crossing the moon, each on its own slow loop. */
    private void drawBats(Canvas c, float s) {
        fx.setColor(0xFF0A0512);
        for (int i = 0; i < 3; i++) {
            float t = ((s + i * 9f) % (26f + i * 5)) / (26f + i * 5);
            float x = 920 + 950 * t, y = 170 + i * 85 + 34 * (float) Math.sin(t * 19 + i);
            float flap = 0.35f + 0.65f * Math.abs((float) Math.sin(s * 9 + i));
            float span = 24 - i * 4;
            Path b = new Path();
            b.moveTo(x, y);
            b.quadTo(x - span * 0.5f, y - span * flap, x - span, y - span * flap * 0.3f);
            b.quadTo(x - span * 0.45f, y + 2, x, y + 7);
            b.quadTo(x + span * 0.45f, y + 2, x + span, y - span * flap * 0.3f);
            b.quadTo(x + span * 0.5f, y - span * flap, x, y);
            c.drawPath(b, fx);
        }
    }

    private void drawRain(Canvas c, float s) {
        rain.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < 150; i++) {
            float depth = (i % 3) / 2f;                         // 0 far, 1 near
            rain.setStrokeWidth(1.2f + depth * 1.2f);
            rain.setColor(Scene.alpha(0xFFC8D6E6, 0.25f + 0.3f * depth));
            float speed = 1100 + depth * 700 + (i * 37) % 300;
            float len = 18 + depth * 20;
            float y = (i * 137 + s * speed) % (H + 60) - 30;
            float x = ((i * 211) % (W + 200)) - (y * 0.1f);
            c.drawLine(x, y, x - len * 0.12f, y + len, rain);
        }
    }

    private void drawSnow(Canvas c, float s) {
        for (int i = 0; i < 140; i++) {
            float depth = (i % 4) / 3f;                         // 0 far, 1 near
            float r = 1.3f + depth * 3.2f + (i % 3) * 0.3f;
            float speed = 26 + depth * 50;
            float y = (i * 113 + s * speed) % (H + 20) - 10;
            float x = ((i * 233) % W) + (14 + depth * 22) * (float) Math.sin(s * (0.4f + depth * 0.4f) + i);
            fx.setColor(Color.argb(Math.round(110 + depth * 130), 255, 255, 255));
            c.drawCircle(x, y, r, fx);
        }
    }
}
