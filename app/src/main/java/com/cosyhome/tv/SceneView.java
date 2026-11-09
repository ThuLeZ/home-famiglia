package com.cosyhome.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/**
 * The landscape behind the clock: Tuscan-style hills fading into the sky with distance, an avenue of
 * cypresses up to a farmhouse with a little tower, an umbrella pine, the sun or the moon.
 *
 * Drawn on a 1920x1080 design canvas and scaled to the view. Everything that never moves is painted
 * once per phase into {@link #still}; each frame only adds the stars, clouds, smoke and weather on
 * top, at about 25 fps, so the TV's GPU is mostly drawing one bitmap.
 */
public class SceneView extends View {

    enum Phase { DAWN, DAY, DUSK, NIGHT }

    interface Listener {
        /** A new still image (and blurred copy) is ready: the phase or the size changed. */
        void onSceneChanged();
    }

    private static final float W = 1920f, H = 1080f;
    private static final long FRAME_MS = 40;

    /** Sky stops, three hill colours from far to near, window colour, window glow, dark clock text. */
    private static final class Palette {
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

    private static final Palette DAWN = new Palette(0xFF323F6E, 0xFFB88AA6, 0xFFF2CBA8,
            0xFF9384A6, 0xFF655A88, 0xFF3C355C, 0xFFFFCB8C, 0.5f, false);
    private static final Palette DAY = new Palette(0xFF5B9DCB, 0xFFA3CCE6, 0xFFE6F1F2,
            0xFFA9C98E, 0xFF7FAE73, 0xFF4F8257, 0xFF3A4048, 0f, true);
    private static final Palette DUSK = new Palette(0xFF2B2650, 0xFFA45B7A, 0xFFF1AA6C,
            0xFF8C5769, 0xFF5B3456, 0xFF2D1D37, 0xFFFFBE6E, 0.75f, false);
    private static final Palette NIGHT = new Palette(0xFF060914, 0xFF0F1835, 0xFF202D55,
            0xFF1F2946, 0xFF161D35, 0xFF0D1224, 0xFFFFBE6E, 1f, false);

    /** Hill colours of each season by daylight, far to near. */
    private static final int[][] SEASON_HILLS = {
            /* CHRISTMAS */ {0xFFF0F5F9, 0xFFDCE6EE, 0xFFC3D2DE},
            /* HALLOWEEN */ {0xFF3A1E46, 0xFF261433, 0xFF120A1C},
            /* SPRING    */ {0xFFB3D08F, 0xFF86B46F, 0xFF55875A},
            /* SUMMER    */ {0xFFD6C983, 0xFFADAE62, 0xFF718B47},
            /* AUTUMN    */ {0xFFD9B072, 0xFFB57E47, 0xFF7B4F31},
            /* WINTER    */ {0xFFBAC7CA, 0xFF93A4A9, 0xFF677980},
    };

    // The landscape's features, on the design canvas before the lift.
    private static final float HOUSE_X = 1470, HOUSE_BASE = 694;
    private static final float HOUSE_SCALE = 1.3f;
    private static final float CHIMNEY_X = 1512, CHIMNEY_TOP = 616;
    private static final float TREE_X = 1372, TREE_BASE = 706, TREE_H = 190;
    /** Pumpkins' centres and radii, for the candle flicker. */
    private static final float[][] CANDLES = {{1296, 700, 26}, {1350, 692, 38}, {1408, 704, 22}};
    /** The pumpkin's ribs as {x offset, half width, shade}, all in units of its radius. */
    private static final float[][] LOBES = {{-0.62f, 0.5f, 0}, {0.62f, 0.5f, 0}, {-0.3f, 0.5f, 1}, {0.3f, 0.5f, 1}, {0f, 0.46f, 0}};

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

    private Phase phase = Phase.NIGHT;
    private Palette pal = NIGHT;
    private Season season = Season.SPRING;
    private Season.Fx sky = Season.Fx.CLEAR;
    /** How far the hills and everything on them sit above the bottom, to clear the rows of tiles. */
    private float lift;
    private Bitmap still;
    private Bitmap blurred;
    /** Soft cloud shapes, drawn once per still and moved each frame. Null when the sky has none. */
    private Bitmap[] clouds;
    private Listener listener;
    private boolean running;
    private final long start = SystemClock.uptimeMillis();
    /** For the still layers. Shaders draw with the paint's alpha, so it must never carry a frame's fade. */
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** For the per-frame stars, clouds and smoke, whose colours carry their own fades. */
    private final Paint fx = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint rain = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mountains = new Path(), far = new Path(), mid = new Path(), near = new Path(), front = new Path();

    public SceneView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mountains.moveTo(0, 600);
        mountains.cubicTo(160, 562, 300, 586, 420, 572);
        mountains.cubicTo(560, 554, 660, 600, 820, 592);
        mountains.cubicTo(980, 584, 1080, 546, 1240, 560);
        mountains.cubicTo(1400, 574, 1520, 538, 1700, 564);
        mountains.cubicTo(1800, 578, 1870, 570, 1920, 574);
        closeToBottom(mountains);
        far.moveTo(0, 650);
        far.cubicTo(220, 612, 420, 628, 640, 646);
        far.cubicTo(860, 664, 1040, 612, 1280, 618);
        far.cubicTo(1500, 624, 1700, 660, 1920, 640);
        closeToBottom(far);
        mid.moveTo(0, 716);
        mid.cubicTo(260, 680, 520, 700, 760, 722);
        mid.cubicTo(1000, 744, 1240, 680, 1480, 690);
        mid.cubicTo(1680, 698, 1820, 708, 1920, 700);
        closeToBottom(mid);
        near.moveTo(0, 830);
        near.cubicTo(300, 784, 640, 798, 900, 822);
        near.cubicTo(1160, 846, 1440, 792, 1700, 798);
        near.cubicTo(1800, 800, 1880, 808, 1920, 812);
        closeToBottom(near);
        front.moveTo(0, 940);
        front.cubicTo(400, 900, 800, 918, 1100, 936);
        front.cubicTo(1400, 954, 1700, 914, 1920, 922);
        closeToBottom(front);
    }

    private static void closeToBottom(Path p) {
        p.lineTo(W, H);
        p.lineTo(0, H);
        p.close();
    }

    void setListener(Listener l) {
        listener = l;
    }

    Phase phase() {
        return phase;
    }

    /** Daytime has a light sky, so the clock switches to dark text; rain dims it again. */
    boolean wantsDarkText() {
        return pal.darkText && sky != Season.Fx.RAIN;
    }

    /** A small, heavily blurred copy of the still scene, for the glass panels to sample. */
    Bitmap blurred() {
        return blurred;
    }

    /** Halloween is always after dark. Redraws the still only when something in it changed. */
    void update(Phase p, Season s, Season.Fx f) {
        if (s == Season.HALLOWEEN && p != Phase.DUSK) p = Phase.NIGHT;
        if (p == phase && s == season && f == sky && still != null) return;
        phase = p;
        season = s;
        sky = f;
        pal = palette(p, s);
        rebuild();
    }

    void setLift(float designPixels) {
        if (designPixels == lift) return;
        lift = designPixels;
        if (still != null) rebuild();
    }

    private static Palette palette(Phase p, Season s) {
        Palette b = p == Phase.DAWN ? DAWN : p == Phase.DAY ? DAY : p == Phase.DUSK ? DUSK : NIGHT;
        int[] h = SEASON_HILLS[s.ordinal()];
        switch (s) {
            case HALLOWEEN:
                return p == Phase.DUSK
                        ? new Palette(0xFF1C0E3A, 0xFF6A2358, 0xFFE8722A, 0xFF4A2650, 0xFF2E1838, 0xFF160C20, 0xFFFFB347, 1f, false)
                        : new Palette(0xFF0C0720, 0xFF27104A, 0xFF6E2C40, 0xFF2C173E, 0xFF1C0F2C, 0xFF0E0718, 0xFFFFB347, 1f, false);
            case CHRISTMAS:
                if (p == Phase.DAY) return b.hills(h[0], h[1], h[2]);
                if (p == Phase.NIGHT) return b.hills(0xFF4A5880, 0xFF38446A, 0xFF283253);
                return b.hills(0xFFCFC8DC, 0xFFAAA2C2, 0xFF7E789C);
            default:
                if (p == Phase.NIGHT) return b;
                if (p == Phase.DAY) return b.hills(h[0], h[1], h[2]);
                // Dawn and dusk tint the season's colours with their own light.
                return b.hills(mix(h[0], b.h1, 0.55f), mix(h[1], b.h2, 0.55f), mix(h[2], b.h3, 0.55f));
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
        clouds = phase == Phase.NIGHT || sky == Season.Fx.RAIN ? null : cloudSprites(w / W);
        blurred = Blur.of(bmp, 8, 5);
        invalidate();
        if (listener != null) listener.onSceneChanged();
    }

    // ---- colour helpers ---------------------------------------------------------------------------

    static int mix(int a, int b, float t) {
        float u = 1 - t;
        return Color.argb(Math.round(Color.alpha(a) * u + Color.alpha(b) * t),
                Math.round(Color.red(a) * u + Color.red(b) * t),
                Math.round(Color.green(a) * u + Color.green(b) * t),
                Math.round(Color.blue(a) * u + Color.blue(b) * t));
    }

    private static int alpha(int color, float a) {
        return (Math.round(Math.max(0, Math.min(1, a)) * 255) << 24) | (color & 0xFFFFFF);
    }

    // ---- the still layers -------------------------------------------------------------------------

    private void drawStill(Canvas c) {
        // Sky: deep at the top, light at the horizon.
        paint.setShader(new LinearGradient(0, 0, 0, 700 - lift, new int[]{pal.s1, pal.s2, pal.s3},
                new float[]{0f, 0.62f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, paint);
        paint.setShader(null);

        boolean overcast = sky == Season.Fx.RAIN && season != Season.HALLOWEEN;
        c.save();
        c.translate(0, -lift);
        // The low sun warms a wide stretch of sky along the horizon.
        if (!overcast && (phase == Phase.DAWN || phase == Phase.DUSK)) {
            float x = phase == Phase.DAWN ? 1180 : 760;
            ellipseGlow(c, x, 620, 820, 260, phase == Phase.DAWN ? 0xFFFFD9B0 : 0xFFFFB878, 0.55f);
        }
        if (!overcast) drawSunOrMoon(c);
        c.restore();

        if (season == Season.HALLOWEEN) {
            // A big low moon, with a soft halo and a couple of craters.
            glowDisc(c, 1420, 280, 140, 0xFFF4C766, 0xFFF0A040, 0.32f);
            fill(c, circle(1365, 232, 22), 0x33B87A2A);
            fill(c, circle(1462, 322, 32), 0x2EB87A2A);
            fill(c, circle(1480, 214, 13), 0x2EB87A2A);
        }

        c.save();
        c.translate(0, -lift);
        int haze = pal.s3;
        // Far to near, each layer a little less veiled by the air between it and us.
        int mountain = mix(pal.h1, haze, 0.62f), l1 = mix(pal.h1, haze, 0.3f), l2 = pal.h1,
                l3 = mix(pal.h2, pal.h3, 0.15f), l4 = pal.h3;
        hill(c, mountains, mountain, 560, 720);
        hill(c, far, l1, 615, 760);
        // A few far cypresses on the first ridge, almost lost in the haze.
        int cypress = mix(pal.h3, 0xFF0E1A12, phase == Phase.NIGHT ? 0.3f : 0.55f);
        int farCypress = mix(cypress, haze, 0.45f);
        cypressAt(c, 404, 634, 34, farCypress);
        cypressAt(c, 422, 633, 40, farCypress);
        cypressAt(c, 441, 634, 30, farCypress);
        cypressAt(c, 1006, 634, 32, farCypress);
        cypressAt(c, 1024, 632, 38, farCypress);

        hill(c, mid, l2, 680, 820);
        if (pal.glow > 0) {
            int a = Math.round(pal.glow * 0x88);
            paint.setShader(new RadialGradient(HOUSE_X + 70, HOUSE_BASE - 20, 170, (a << 24) | (pal.win & 0xFFFFFF),
                    pal.win & 0xFFFFFF, Shader.TileMode.CLAMP));
            c.drawCircle(HOUSE_X + 70, HOUSE_BASE - 20, 170, paint);
            paint.setShader(null);
        }
        drawRoad(c, mix(l2, 0xFFFFFFFF, phase == Phase.NIGHT ? 0.06f : 0.28f));
        umbrellaPine(c, 800, 724, mix(cypress, l2, 0.12f));
        drawHouse(c);
        // The avenue: a cypress on each side of the road along the ridge, smaller as it nears the
        // farmhouse. Far ones first, so the nearer ones stand in front of them.
        for (int i = 7; i >= 0; i--) {
            float t = i / 7f;
            float x = 1110 + 330 * t, y = 742 - 40 * t;
            float hgt = 76 - 30 * t;
            cypressAt(c, x, y - 8 + 4 * t, hgt, cypress);
            cypressAt(c, x + 14, y + 10 - 6 * t, hgt * 1.06f, cypress);
        }
        cypressAt(c, 1668, 704, 70, cypress);
        cypressAt(c, 1690, 706, 54, cypress);
        if (season == Season.CHRISTMAS) drawChristmas(c);
        if (season == Season.HALLOWEEN) drawHalloween(c);

        hill(c, near, l3, 790, 980);
        rows(c, near, mix(l3, pal.h3, 0.5f), 0.22f);
        hill(c, front, l4, 910, 1080);
        paint.setColor(mix(l4, 0xFF000000, 0.14f));   // the colour the front hill fades to
        c.drawRect(0, H, W, H + lift, paint);
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
        switch (phase) {
            case DAWN:
                glowDisc(c, 1180, 612, 60, 0xFFFFE6C4, 0xFFFFD39C, 0.5f);
                break;
            case DAY:
                glowDisc(c, 1500, 210 + lift, 54, 0xFFFFFBEC, 0xFFFFF4D2, 0.5f);
                break;
            case DUSK:
                glowDisc(c, 760, 622, 70, 0xFFFFD29A, 0xFFFFB46B, 0.55f);
                break;
            case NIGHT:
                if (season == Season.HALLOWEEN) break;
                glow(c, 1500, 210 + lift, 46, 0xFFE8E4D6, 0.18f);
                // The crescent: the moon's disc less an offset shadow disc, so the unlit part never
                // reaches past the moon's edge.
                Path moon = circle(1500, 210 + lift, 46);
                moon.op(circle(1520, 197 + lift, 41), Path.Op.DIFFERENCE);
                paint.setShader(new LinearGradient(1454, 0, 1546, 0, 0xFFF6F0DE, 0xFFD8D0BC, Shader.TileMode.CLAMP));
                c.drawPath(moon, paint);
                paint.setShader(null);
                break;
        }
    }

    /** A hill filled with a gentle top-to-bottom shade, lighter on the crest. */
    private void hill(Canvas c, Path p, int color, float top, float bottom) {
        paint.setShader(new LinearGradient(0, top, 0, bottom, mix(color, 0xFFFFFFFF, 0.05f),
                mix(color, 0xFF000000, 0.14f), Shader.TileMode.CLAMP));
        c.drawPath(p, paint);
        paint.setShader(null);
    }

    /** Ploughed rows or vines: faint curves that follow the hill, clipped to it. */
    private void rows(Canvas c, Path hill, int color, float strength) {
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

    /** The white road winding up through the avenue. */
    private void drawRoad(Canvas c, int color) {
        Path road = new Path();
        road.moveTo(1040, 760);
        road.cubicTo(1180, 744, 1300, 730, 1380, 716);
        road.cubicTo(1420, 709, 1450, 703, 1470, 698);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(7);
        paint.setColor(alpha(color, 0.7f));
        c.drawPath(road, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    /** A Tuscan cypress: a tall slim flame, rounded at the base and pointed at the top. */
    private void cypressAt(Canvas c, float x, float base, float h, int color) {
        float w = h * 0.23f;
        // Its shadow on the grass, then the tree itself, tapering to a short trunk at the base.
        fill(c, oval(x + w * 0.3f, base, w * 0.8f, w * 0.14f), alpha(0xFF000000, 0.16f));
        Path p = new Path();
        p.moveTo(x - w * 0.08f, base);
        p.cubicTo(x - w * 0.7f, base - h * 0.08f, x - w * 0.55f, base - h * 0.55f, x - w * 0.12f, base - h * 0.92f);
        p.quadTo(x, base - h * 1.04f, x + w * 0.12f, base - h * 0.92f);
        p.cubicTo(x + w * 0.55f, base - h * 0.55f, x + w * 0.7f, base - h * 0.08f, x + w * 0.08f, base);
        p.close();
        paint.setShader(new LinearGradient(x - w * 0.5f, 0, x + w * 0.5f, 0, mix(color, 0xFFFFFFFF, 0.08f), color,
                Shader.TileMode.CLAMP));
        c.drawPath(p, paint);
        paint.setShader(null);
    }

    /** An umbrella pine: a leaning trunk and a wide, flat crown. */
    private void umbrellaPine(Canvas c, float x, float base, int color) {
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
        paint.setShader(new LinearGradient(0, base - 190, 0, base - 124, mix(color, 0xFFFFFFFF, 0.14f),
                mix(color, 0xFF000000, 0.1f), Shader.TileMode.CLAMP));
        c.drawPath(crown, paint);
        paint.setShader(null);
    }

    /** The farmhouse: a long stone house, a dovecote tower and terracotta roofs. */
    private void drawHouse(Canvas c) {
        int stone, roof;
        switch (phase) {
            case DAY:
                stone = 0xFFEAD7B5;
                roof = 0xFFB8623C;
                break;
            case DAWN:
                stone = 0xFFD9B9AC;
                roof = 0xFF9A5248;
                break;
            case DUSK:
                stone = 0xFFC79A86;
                roof = 0xFF8A4638;
                break;
            default:
                stone = 0xFF39405E;
                roof = 0xFF2A2F4A;
        }
        if (season == Season.HALLOWEEN) {
            stone = 0xFF2C2238;
            roof = 0xFF1C1428;
        }
        int shade = mix(stone, 0xFF000000, 0.22f);
        float x = HOUSE_X, b = HOUSE_BASE;
        c.save();
        c.scale(HOUSE_SCALE, HOUSE_SCALE, x + 70, b);
        // The long house.
        paint.setColor(stone);
        c.drawRect(x, b - 48, x + 112, b, paint);
        roof(c, x - 8, b - 46, x + 120, b - 70, roof);
        // A chimney on its roof.
        paint.setColor(mix(stone, 0xFF000000, 0.1f));
        c.drawRect(CHIMNEY_X - 6, CHIMNEY_TOP, CHIMNEY_X + 6, b - 58, paint);
        // The tower, its shaded side and its small pyramid roof.
        paint.setColor(stone);
        c.drawRect(x + 104, b - 96, x + 146, b, paint);
        paint.setColor(shade);
        c.drawRect(x + 132, b - 96, x + 146, b, paint);
        roof(c, x + 98, b - 94, x + 152, b - 114, roof);
        // A low barn on the other side.
        paint.setColor(shade);
        c.drawRect(x - 46, b - 26, x, b, paint);
        roof(c, x - 52, b - 24, x + 2, b - 36, roof);

        int win = pal.win;
        paint.setColor(win);
        for (int i = 0; i < 3; i++) c.drawRect(x + 16 + i * 32, b - 34, x + 26 + i * 32, b - 20, paint);
        c.drawRect(x + 116, b - 78, x + 124, b - 64, paint);
        // The front door, arched.
        paint.setColor(mix(stone, 0xFF000000, 0.45f));
        c.drawRect(x + 112, b - 22, x + 126, b, paint);
        c.drawCircle(x + 119, b - 22, 7, paint);
        if (pal.glow > 0) {
            for (int i = 0; i < 3; i++) glow(c, x + 21 + i * 32, b - 27, 6, win, 0.5f * pal.glow);
            glow(c, x + 120, b - 71, 5, win, 0.5f * pal.glow);
        }
        if (season == Season.CHRISTMAS) {
            // Snow on the roofs.
            snowCap(c, x - 8, b - 46, x + 120, b - 70);
            snowCap(c, x + 98, b - 94, x + 152, b - 114);
            snowCap(c, x - 52, b - 24, x + 2, b - 36);
        }
        c.restore();
    }

    /** Where a point of the farmhouse drawing lands once the house is scaled up. */
    private static float houseX(float x) {
        return HOUSE_X + 70 + (x - HOUSE_X - 70) * HOUSE_SCALE;
    }

    private static float houseY(float y) {
        return HOUSE_BASE + (y - HOUSE_BASE) * HOUSE_SCALE;
    }

    /** A low-pitched hip roof from (x0, eave) to (x1, eave) with its ridge at {@code ridge}. */
    private void roof(Canvas c, float x0, float eave, float x1, float ridge, int color) {
        Path p = new Path();
        float inset = (x1 - x0) * 0.22f;
        p.moveTo(x0, eave);
        p.lineTo(x0 + inset, ridge);
        p.lineTo(x1 - inset, ridge);
        p.lineTo(x1, eave);
        p.close();
        paint.setShader(new LinearGradient(0, ridge, 0, eave, mix(color, 0xFFFFFFFF, 0.12f), color, Shader.TileMode.CLAMP));
        c.drawPath(p, paint);
        paint.setShader(null);
    }

    private void snowCap(Canvas c, float x0, float eave, float x1, float ridge) {
        Path p = new Path();
        float inset = (x1 - x0) * 0.22f, depth = (eave - ridge) * 0.45f;
        p.moveTo(x0 + 2, eave - depth * 0.4f);
        p.lineTo(x0 + inset, ridge);
        p.lineTo(x1 - inset, ridge);
        p.lineTo(x1 - 2, eave - depth * 0.4f);
        p.quadTo((x0 + x1) / 2, ridge + depth * 1.2f, x0 + 2, eave - depth * 0.4f);
        fill(c, p, 0xFFF4F8FC);
    }

    private static Path circle(float x, float y, float r) {
        Path p = new Path();
        p.addCircle(x, y, r, Path.Direction.CW);
        return p;
    }

    /** A slim fir by the farmhouse, its tiers with soft scalloped edges, a star on top. */
    private void drawChristmas(Canvas c) {
        float x = TREE_X, b = TREE_BASE, h = TREE_H;
        paint.setColor(0xFF4A2E1E);
        c.drawRect(x - 7, b - 18, x + 7, b, paint);
        int green = phase == Phase.NIGHT ? 0xFF17452F : phase == Phase.DAY ? 0xFF2B6A48 : 0xFF1F5539;
        int tiers = 4;
        for (int i = 0; i < tiers; i++) {
            float top = b - h + i * h * 0.2f, bottom = b - 16 - (tiers - 1 - i) * h * 0.17f;
            float half = (bottom - (b - h)) * 0.38f;
            Path p = new Path();
            p.moveTo(x, top);
            p.quadTo(x + half * 0.45f, top + (bottom - top) * 0.55f, x + half, bottom);
            // The scalloped hem, three soft dips.
            for (int k = 0; k < 3; k++) {
                float x0 = x + half - k * half * 2 / 3f, x1 = x0 - half * 2 / 3f;
                p.quadTo((x0 + x1) / 2, bottom - 7, x1, bottom);
            }
            p.quadTo(x - half * 0.45f, top + (bottom - top) * 0.55f, x, top);
            p.close();
            paint.setShader(new LinearGradient(x - half, 0, x + half, 0, mix(green, 0xFFFFFFFF, 0.12f),
                    mix(green, 0xFF000000, 0.2f), Shader.TileMode.CLAMP));
            c.drawPath(p, paint);
            paint.setShader(null);
        }
        int[] baubles = {0xFFC8323F, 0xFFE6B84A, 0xFFC8323F, 0xFFE6B84A, 0xFFC8323F, 0xFFE6B84A};
        for (int i = 0; i < baubles.length; i++) {
            float t = (i + 1) / 7f;
            float by = b - h + h * 0.9f * t + 4, spread = (by - (b - h)) * 0.3f;
            fill(c, circle(x + (i % 2 == 0 ? -1 : 1) * spread * 0.6f, by, 4.5f), baubles[i]);
        }
        star(c, x, b - h - 4, 13, 0xFFFFD86A);
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

    private void drawHalloween(Canvas c) {
        // A bare old tree, more spooky than scary.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(0xFF0A0512);
        paint.setStrokeWidth(22);
        c.drawLine(1780, 720, 1786, 520, paint);
        paint.setStrokeWidth(11);
        c.drawLine(1784, 600, 1700, 520, paint);
        c.drawLine(1786, 560, 1850, 470, paint);
        paint.setStrokeWidth(6);
        c.drawLine(1730, 548, 1676, 544, paint);
        c.drawLine(1716, 534, 1712, 480, paint);
        c.drawLine(1828, 498, 1790, 450, paint);
        paint.setStyle(Paint.Style.FILL);
        // Jack-o'-lanterns with round goofy faces, by the farmhouse.
        for (int i = 0; i < CANDLES.length; i++) pumpkin(c, CANDLES[i][0], CANDLES[i][1], CANDLES[i][2], i);
    }

    private void pumpkin(Canvas c, float x, float y, float r, int face) {
        fill(c, oval(x, y + r * 0.9f, r * 1.05f, r * 0.2f), 0x66000000);
        paint.setColor(0xFF4C6B2A);
        c.drawRect(x - r * 0.09f, y - r * 1.14f, x + r * 0.09f, y - r * 0.8f, paint);
        for (float[] lobe : LOBES) {
            fill(c, oval(x + lobe[0] * r, y, lobe[1] * r, r * 0.92f), lobe[2] == 0 ? 0xFFD9651A : 0xFFEB7A22);
        }
        int light = 0xFFFFD56A;
        // Triangle eyes and a toothy grin that glow from inside; each pumpkin pulls a different face.
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

    private static Path oval(float x, float y, float rx, float ry) {
        Path p = new Path();
        p.addOval(x - rx, y - ry, x + rx, y + ry, Path.Direction.CW);
        return p;
    }

    private void tri(Canvas c, float x, float y, float r, int up, int color) {
        Path p = new Path();
        p.moveTo(x, y - up * r);
        p.lineTo(x + r, y + up * r);
        p.lineTo(x - r, y + up * r);
        p.close();
        fill(c, p, color);
    }

    /** A disc with a soft bloom, a touch lighter at its centre. */
    private void glowDisc(Canvas c, float x, float y, float r, int disc, int glow, float glowAlpha) {
        glow(c, x, y, r, glow, glowAlpha);
        glow(c, x, y, r * 0.55f, glow, glowAlpha * 0.8f);
        paint.setShader(new RadialGradient(x - r * 0.25f, y - r * 0.25f, r * 1.2f, mix(disc, 0xFFFFFFFF, 0.5f), disc,
                Shader.TileMode.CLAMP));
        c.drawCircle(x, y, r, paint);
        paint.setShader(null);
    }

    /** The soft halo around a disc of radius r, without the disc itself. */
    private void glow(Canvas c, float x, float y, float r, int color, float alpha) {
        paint.setShader(new RadialGradient(x, y, r * 2.8f, new int[]{alpha(color, alpha), alpha(color, alpha * 0.35f),
                color & 0xFFFFFF}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, r * 2.8f, paint);
        paint.setShader(null);
    }

    /** A wide, flat glow: an elliptical radial gradient. */
    private void ellipseGlow(Canvas c, float x, float y, float rx, float ry, int color, float alpha) {
        RadialGradient g = new RadialGradient(0, 0, 1, alpha(color, alpha), color & 0xFFFFFF, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setScale(rx, ry);
        m.postTranslate(x, y);
        g.setLocalMatrix(m);
        paint.setShader(g);
        c.drawOval(x - rx, y - ry, x + rx, y + ry, paint);
        paint.setShader(null);
    }

    private void fill(Canvas c, Path p, int color) {
        paint.setColor(color);
        c.drawPath(p, paint);
    }

    // ---- clouds -----------------------------------------------------------------------------------

    /** Three soft clouds at screen resolution, coloured by the light of the phase. */
    private Bitmap[] cloudSprites(float scale) {
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
        float seconds = (SystemClock.uptimeMillis() - start) / 1000f;
        float sx = getWidth() / W;
        canvas.save();
        canvas.scale(sx, getHeight() / H);
        boolean overcast = sky == Season.Fx.RAIN;
        if (!overcast && (phase == Phase.NIGHT || phase == Phase.DUSK)) {
            drawStars(canvas, seconds, phase == Phase.NIGHT ? 1f : 0.3f);
        }
        if (clouds != null) drawClouds(canvas, seconds, sx);
        canvas.save();
        canvas.translate(0, -lift);
        if (phase != Phase.DAY) drawSmoke(canvas, seconds);
        if (season == Season.CHRISTMAS) drawLights(canvas, seconds);
        if (season == Season.HALLOWEEN) drawCandles(canvas, seconds);
        canvas.restore();
        if (season == Season.HALLOWEEN) drawBats(canvas, seconds);
        if (sky == Season.Fx.RAIN) drawRain(canvas, seconds);
        else if (sky == Season.Fx.SNOW) drawSnow(canvas, seconds);
        canvas.restore();
        if (running) postInvalidateDelayed(FRAME_MS);
    }

    private void drawStars(Canvas c, float s, float strength) {
        float horizon = 600 - lift;
        for (int i = 0; i < STARS.length; i++) {
            float[] st = STARS[i];
            if (st[1] > horizon) continue;
            // Fainter towards the horizon; every other star twinkles on its own slow period.
            float fade = 1f - 0.7f * (st[1] / horizon);
            float twinkle = i % 2 == 0 ? 1f : 0.6f + 0.4f * (float) Math.sin(s * (0.7f + (i % 7) * 0.13f) + i);
            int a = Math.round(235 * strength * fade * twinkle);
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
        fx.setColor(Color.argb(phase == Phase.DAY ? 220 : 170, 255, 255, 255));
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
            c.drawCircle(houseX(CHIMNEY_X) + 26 * t + 6 * (float) Math.sin(t * 6), houseY(CHIMNEY_TOP) - 6 - 110 * t, r, fx);
        }
    }

    /** Warm fairy lights: garlands round the fir, and along the farmhouse eaves. */
    private void drawLights(Canvas c, float s) {
        float x = TREE_X, b = TREE_BASE, h = TREE_H;
        int n = 0;
        for (int row = 0; row < 3; row++) {
            float y0 = b - h + 44 + row * 48, half = (y0 - (b - h)) * 0.36f;
            for (int k = 0; k <= 4; k++, n++) {
                float t = k / 4f;
                float px = x - half + 2 * half * t, py = y0 + 10 * t - 4 * (float) Math.sin(t * Math.PI);
                light(c, px, py, s, n);
            }
        }
        for (int k = 0; k < 8; k++, n++) light(c, houseX(HOUSE_X - 4 + k * 17), houseY(HOUSE_BASE - 46), s, n);
    }

    private void light(Canvas c, float x, float y, float s, int i) {
        float on = 0.55f + 0.45f * (float) Math.sin(s * (1.1f + (i % 5) * 0.23f) + i * 1.7f);
        int color = i % 3 == 0 ? 0xFFFFE6A0 : 0xFFFFD27A;
        fx.setColor(alpha(color, 0.25f * on));
        c.drawCircle(x, y, 7, fx);
        fx.setColor(alpha(color, 0.6f + 0.4f * on));
        c.drawCircle(x, y, 2.4f, fx);
    }

    private void drawCandles(Canvas c, float s) {
        for (int i = 0; i < CANDLES.length; i++) {
            float[] p = CANDLES[i];
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
            rain.setColor(alpha(0xFFC8D6E6, 0.25f + 0.3f * depth));
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
