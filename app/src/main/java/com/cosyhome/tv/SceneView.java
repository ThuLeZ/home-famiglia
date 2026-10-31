package com.cosyhome.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/**
 * The cosy valley behind the clock: sky, sun or moon, three hills and a cottage.
 *
 * Drawn on a 1920x1080 design canvas and scaled to the view. Everything that never moves is
 * painted once per phase into {@link #still}; each frame only adds the stars, clouds and chimney
 * smoke on top, at about 25 fps, so the TV's GPU is mostly drawing one bitmap.
 */
public class SceneView extends View {

    enum Phase { DAWN, DAY, DUSK, NIGHT }

    interface Listener {
        /** A new still image (and blurred copy) is ready: the phase or the size changed. */
        void onSceneChanged();
    }

    private static final float W = 1920f, H = 1080f;
    private static final long FRAME_MS = 40;

    /** Sky stops, three hill colours, window colour, window glow strength, dark clock text. */
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
    }

    private static final Palette DAWN = new Palette(0xFF2E3A6B, 0xFFC98A9A, 0xFFF6C99A,
            0xFF7C6A93, 0xFF54497A, 0xFF2E2A4E, 0xFFFFC27A, 0.5f, false);
    private static final Palette DAY = new Palette(0xFF4F9CCB, 0xFF93C6E4, 0xFFD8EEF6,
            0xFF8DB59A, 0xFF64957A, 0xFF3F6E55, 0xFF2B3A40, 0f, true);
    private static final Palette DUSK = new Palette(0xFF2F2350, 0xFFB4506A, 0xFFF2A65A,
            0xFF7A4560, 0xFF4E2C4A, 0xFF24162E, 0xFFFFB35C, 0.75f, false);
    private static final Palette NIGHT = new Palette(0xFF04060E, 0xFF0B1230, 0xFF1A2550,
            0xFF161D36, 0xFF0E1326, 0xFF070A14, 0xFFFFB35C, 1f, false);

    private static final float[][] STARS = {
            {180, 90, 2}, {420, 180, 1.6f}, {690, 70, 2.2f}, {930, 150, 1.4f}, {1150, 60, 2},
            {1320, 130, 1.5f}, {1720, 90, 2.2f}, {1830, 260, 1.4f}, {1080, 300, 1.4f}, {820, 380, 1.2f},
            {1620, 400, 1.3f}, {560, 330, 1.2f}, {300, 420, 1.1f}, {1240, 240, 1.2f}, {80, 250, 1.5f}};
    /** Cloud puffs as {cx, cy, rx, ry}. */
    private static final float[][] CLOUDS = {
            {420, 260, 130, 34}, {500, 240, 90, 40}, {1000, 380, 160, 30}, {1080, 362, 80, 34}, {1760, 430, 120, 26}};

    private static final float[][] RAIN_CLOUDS = {
            {260, 40, 420, 120}, {800, 20, 460, 110}, {1400, 50, 480, 130}, {1820, 20, 320, 110},
            {560, 120, 300, 70}, {1120, 110, 280, 70}, {1650, 130, 280, 70}};
    /** Baubles on the Christmas tree: x, y, colour. */
    private static final float[][] ORNAMENTS = {
            {1165, 500, 0xFFE0434F}, {1262, 520, 0xFFF2C744}, {1210, 450, 0xFF4FA3E0},
            {1120, 640, 0xFFF2C744}, {1200, 610, 0xFFE0434F}, {1296, 650, 0xFF4FA3E0},
            {1100, 770, 0xFFE0434F}, {1190, 740, 0xFF4FA3E0}, {1282, 760, 0xFFF2C744}, {1340, 780, 0xFFE0434F}};
    /** Twinkling lights on the tree. */
    private static final float[][] LIGHTS = {
            {1190, 420}, {1236, 470}, {1150, 540}, {1270, 580}, {1215, 540}, {1090, 690},
            {1170, 680}, {1250, 690}, {1330, 710}, {1130, 790}, {1230, 800}, {1310, 800}};
    /** The pumpkin's ribs as {x offset, half width, shade}, all in units of its radius. */
    private static final float[][] LOBES = {{-0.62f, 0.5f, 0}, {0.62f, 0.5f, 0}, {-0.3f, 0.5f, 1}, {0.3f, 0.5f, 1}, {0f, 0.46f, 0}};
    /** Pumpkins' centres and radii, for the candle flicker. */
    private static final float[][] CANDLES = {{1010, 770, 72}, {1215, 742, 112}, {1395, 780, 62}};

    private Phase phase = Phase.NIGHT;
    private Palette pal = NIGHT;
    private Season season = Season.SPRING;
    private Season.Fx sky = Season.Fx.CLEAR;
    private final Paint rain = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** How far the hills and everything on them sit above the bottom, to clear the rows of tiles. */
    private float lift;
    private Bitmap still;
    private Bitmap blurred;
    private Listener listener;
    private boolean running;
    private final long start = SystemClock.uptimeMillis();
    /** For the still layers. Shaders draw with the paint's alpha, so it must never carry a frame's fade. */
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** For the per-frame stars, clouds and smoke, whose colours carry their own fades. */
    private final Paint fx = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path far = new Path(), mid = new Path(), near = new Path();

    public SceneView(Context context, AttributeSet attrs) {
        super(context, attrs);
        far.moveTo(0, 720);
        far.cubicTo(240, 640, 420, 660, 640, 700);
        far.cubicTo(860, 740, 1040, 640, 1280, 650);
        far.cubicTo(1500, 660, 1700, 720, 1920, 690);
        closeToBottom(far);
        mid.moveTo(0, 820);
        mid.cubicTo(260, 760, 520, 790, 760, 820);
        mid.cubicTo(1000, 850, 1240, 760, 1500, 770);
        mid.cubicTo(1700, 778, 1820, 800, 1920, 790);
        closeToBottom(mid);
        near.moveTo(0, 940);
        near.cubicTo(300, 880, 640, 900, 900, 930);
        near.cubicTo(1160, 960, 1440, 900, 1700, 905);
        near.cubicTo(1800, 907, 1880, 915, 1920, 920);
        closeToBottom(near);
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
        switch (s) {
            case HALLOWEEN:
                return p == Phase.DUSK
                        ? new Palette(0xFF1C0E3A, 0xFF6A2358, 0xFFE8722A, 0xFF3A1E46, 0xFF261433, 0xFF120A1C, 0xFFFFB347, 1f, false)
                        : new Palette(0xFF0C0720, 0xFF27104A, 0xFF7A2E3A, 0xFF241338, 0xFF170C26, 0xFF0B0614, 0xFFFFB347, 1f, false);
            case CHRISTMAS:
                switch (p) {
                    case DAY:
                        return new Palette(b.s1, b.s2, b.s3, 0xFFEAF2F8, 0xFFD8E5EF, 0xFFBED0DF, b.win, b.glow, true);
                    case NIGHT:
                        return new Palette(b.s1, b.s2, b.s3, 0xFF47547A, 0xFF364266, 0xFF27304F, b.win, b.glow, false);
                    default:
                        return new Palette(b.s1, b.s2, b.s3, 0xFFC9C3D8, 0xFFA69FBF, 0xFF7C7699, b.win, b.glow, false);
                }
            case SPRING:
                return p == Phase.NIGHT ? b : new Palette(b.s1, b.s2, b.s3, 0xFF9BCB86, 0xFF6DAE6A, 0xFF44834F, b.win, b.glow, b.darkText);
            case SUMMER:
                return p == Phase.NIGHT ? b : new Palette(b.s1, b.s2, b.s3, 0xFFB4C76F, 0xFF87A955, 0xFF5B8340, b.win, b.glow, b.darkText);
            case AUTUMN:
                return p == Phase.NIGHT ? b : new Palette(b.s1, b.s2, b.s3, 0xFFD2A05A, 0xFFB0733A, 0xFF74482A, b.win, b.glow, b.darkText);
            case WINTER:
                return p == Phase.NIGHT ? b : new Palette(b.s1, b.s2, b.s3, 0xFFA9B8BE, 0xFF84979F, 0xFF5D7078, b.win, b.glow, b.darkText);
            default:
                return b;
        }
    }

    /** Frees the full-screen still while an app is in front; the next {@link #update} redraws it. */
    void release() {
        still = null;
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
        blurred = Blur.of(bmp, 8, 5);
        invalidate();
        if (listener != null) listener.onSceneChanged();
    }

    // ---- the still layers -------------------------------------------------------------------------

    private void drawStill(Canvas c) {
        paint.setShader(new LinearGradient(0, 0, 0, H, new int[]{pal.s1, pal.s2, pal.s3},
                new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, paint);
        paint.setShader(null);

        if (sky != Season.Fx.RAIN || season == Season.HALLOWEEN) switch (phase) {
            case DAWN:
                glowDisc(c, 1180, 660, 68, 0xFFFFE2B4, 0xFFFFD9A0, 0.35f);
                break;
            case DAY:
                glowDisc(c, 1500, 200, 66, 0xFFFFF8E2, 0xFFFFF6D6, 0.55f);
                break;
            case DUSK:
                glowDisc(c, 820, 706, 80, 0xFFFFC27E, 0xFFFFB46B, 0.45f);
                break;
            case NIGHT:
                if (season == Season.HALLOWEEN) break;
                glow(c, 1480, 230, 54, 0xFFF1EAD8, 0.16f);
                // The crescent: the moon's disc less an offset shadow disc, so the unlit part
                // never reaches past the moon's edge.
                Path moon = new Path();
                moon.addCircle(1480, 230, 54, Path.Direction.CW);
                Path shadow = new Path();
                shadow.addCircle(1502, 216, 48, Path.Direction.CW);
                moon.op(shadow, Path.Op.DIFFERENCE);
                fill(c, moon, 0xFFF1EAD8);
                break;
        }

        if (season == Season.HALLOWEEN) {
            // A big low moon, with a soft halo and a couple of craters.
            glowDisc(c, 1400, 270, 165, 0xFFF4C766, 0xFFF0A040, 0.34f);
            fill(c, circle(1340, 215, 26), 0x33B87A2A);
            fill(c, circle(1450, 320, 38), 0x2EB87A2A);
            fill(c, circle(1470, 195, 15), 0x2EB87A2A);
        }

        c.save();
        c.translate(0, -lift);
        fill(c, far, pal.h1);
        fill(c, mid, pal.h2);

        if (pal.glow > 0) {
            int a = Math.round(pal.glow * 0x99);
            int core = (a << 24) | (pal.win & 0xFFFFFF);
            paint.setShader(new RadialGradient(1564, 746, 110, core, pal.win & 0xFFFFFF, Shader.TileMode.CLAMP));
            c.drawCircle(1564, 746, 110, paint);
            paint.setShader(null);
        }

        // Cottage, chimney and a few trees on the middle ridge.
        paint.setColor(pal.h3);
        c.drawRect(1520, 722, 1610, 778, paint);
        Path roof = new Path();
        roof.moveTo(1508, 726);
        roof.lineTo(1565, 684);
        roof.lineTo(1622, 726);
        roof.close();
        c.drawPath(roof, paint);
        c.drawRect(1588, 690, 1601, 716, paint);
        tree(c, 1650, 782, 34, 54);
        tree(c, 1690, 784, 38, 68);
        tree(c, 1468, 780, 28, 42);
        paint.setColor(pal.win);
        c.drawRect(1540, 738, 1558, 754, paint);
        c.drawRect(1570, 738, 1588, 754, paint);

        if (season == Season.CHRISTMAS) {
            c.save();
            scaleTree(c);
            drawChristmas(c);
            c.restore();
        }
        fill(c, near, pal.h3);
        paint.setColor(pal.h3);
        c.drawRect(0, H, W, H + lift, paint);
        if (season == Season.HALLOWEEN) drawHalloween(c);
        c.restore();
        if (sky == Season.Fx.RAIN) {
            // Overcast: the whole picture dims, with heavy cloud along the top.
            paint.setColor(0x66202A36);
            c.drawRect(0, 0, W, H, paint);
            paint.setColor(0xB04A525E);
            for (float[] cl : RAIN_CLOUDS) c.drawOval(cl[0] - cl[2], cl[1] - cl[3], cl[0] + cl[2], cl[1] + cl[3], paint);
        }
    }

    private static Path circle(float x, float y, float r) {
        Path p = new Path();
        p.addCircle(x, y, r, Path.Direction.CW);
        return p;
    }

    /** Above three rows of tiles there is less sky: the tree shrinks so its star stays on screen. */
    private void scaleTree(Canvas c) {
        if (lift >= 400) c.scale(0.65f, 0.65f, 1210, 860);
    }

    private void drawChristmas(Canvas c) {
        // Snow on the cottage roof, which stays full size.
        Path cap = new Path();
        cap.moveTo(1508, 726);
        cap.lineTo(1565, 684);
        cap.lineTo(1622, 726);
        cap.lineTo(1600, 716);
        cap.lineTo(1565, 696);
        cap.lineTo(1530, 716);
        cap.close();
        fill(c, cap, 0xFFF4F8FC);
        // The big tree: three tiers, snow on each, a star on top.
        paint.setColor(0xFF4A2E1E);
        c.drawRect(1188, 800, 1232, 862, paint);
        int green = phase == Phase.NIGHT ? 0xFF14482F : phase == Phase.DAY ? 0xFF1F6E45 : 0xFF185A3A;
        float[][] tiers = {{1210, 360, 190, 590}, {1210, 450, 170, 700}, {1210, 570, 150, 810}};
        for (int i = tiers.length - 1; i >= 0; i--) {
            float[] t = tiers[2 - i];
            Path p = new Path();
            p.moveTo(t[0], t[1]);
            p.lineTo(t[0] + (t[3] - t[1]) * 0.56f, t[3]);
            p.lineTo(t[0] - (t[3] - t[1]) * 0.56f, t[3]);
            p.close();
            fill(c, p, green);
        }
        for (float[] b : ORNAMENTS) fill(c, circle(b[0], b[1], 11), (int) b[2]);
        star(c, 1210, 346, 34, 0xFFFFD75E);
        // Presents.
        present(c, 1040, 794, 84, 68, 0xFFC8324B, 0xFFF6E6A8);
        present(c, 1320, 804, 72, 58, 0xFF2C6FB5, 0xFFF4F4F4);
        present(c, 1400, 816, 56, 46, 0xFFE0A030, 0xFFB5283C);
    }

    private void star(Canvas c, float x, float y, float r, int color) {
        glow(c, x, y, r, color, 0.35f);
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

    private void present(Canvas c, float x, float y, float w, float h, int box, int ribbon) {
        paint.setColor(box);
        c.drawRect(x, y, x + w, y + h, paint);
        paint.setColor(ribbon);
        c.drawRect(x + w / 2 - 6, y, x + w / 2 + 6, y + h, paint);
        c.drawRect(x, y + h / 2 - 6, x + w, y + h / 2 + 6, paint);
        fill(c, circle(x + w / 2 - 11, y - 5, 9), ribbon);
        fill(c, circle(x + w / 2 + 11, y - 5, 9), ribbon);
    }

    private void drawHalloween(Canvas c) {
        // A bare old tree, more spooky than scary.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(0xFF0A0512);
        paint.setStrokeWidth(46);
        c.drawLine(1790, 880, 1800, 520, paint);
        paint.setStrokeWidth(22);
        c.drawLine(1798, 640, 1660, 500, paint);
        c.drawLine(1800, 560, 1880, 410, paint);
        paint.setStrokeWidth(12);
        c.drawLine(1700, 540, 1610, 530, paint);
        c.drawLine(1680, 520, 1670, 420, paint);
        c.drawLine(1850, 460, 1790, 380, paint);
        paint.setStyle(Paint.Style.FILL);
        // Jack-o'-lanterns with round goofy faces, sitting on the ridge above the dock.
        pumpkin(c, 1010, 770, 72, 0);
        pumpkin(c, 1215, 742, 112, 1);
        pumpkin(c, 1395, 780, 62, 2);
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
        paint.setColor(0xFFD9651A);
        for (int i = -1; i <= 1; i += 2) {
            c.drawRect(x + i * gw * 0.35f - r * 0.07f, gy, x + i * gw * 0.35f + r * 0.07f, gy + gh * 0.45f, paint);
        }
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

    private void glowDisc(Canvas c, float x, float y, float r, int disc, int glow, float glowAlpha) {
        glow(c, x, y, r, glow, glowAlpha);
        paint.setColor(disc);
        c.drawCircle(x, y, r, paint);
    }

    /** The soft halo around a disc of radius r, without the disc itself. */
    private void glow(Canvas c, float x, float y, float r, int color, float alpha) {
        int a = Math.round(alpha * 255) << 24;
        paint.setShader(new RadialGradient(x, y, r * 2.6f, a | (color & 0xFFFFFF), color & 0xFFFFFF, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, r * 2.6f, paint);
        paint.setShader(null);
    }

    private void tree(Canvas c, float x, float baseY, float w, float h) {
        Path p = new Path();
        p.moveTo(x, baseY);
        p.lineTo(x + w / 2, baseY - h);
        p.lineTo(x + w, baseY);
        p.close();
        c.drawPath(p, paint);
    }

    private void fill(Canvas c, Path p, int color) {
        paint.setColor(color);
        c.drawPath(p, paint);
    }

    // ---- each frame -------------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        if (still == null) return;
        canvas.drawBitmap(still, 0, 0, null);
        float seconds = (SystemClock.uptimeMillis() - start) / 1000f;
        canvas.save();
        canvas.scale(getWidth() / W, getHeight() / H);
        boolean overcast = sky == Season.Fx.RAIN;
        if (!overcast && (phase == Phase.NIGHT || phase == Phase.DUSK)) drawStars(canvas, seconds, phase == Phase.NIGHT ? 1f : 0.35f);
        if (!overcast && (phase == Phase.DAWN || phase == Phase.DAY)) drawClouds(canvas, seconds);
        canvas.save();
        canvas.translate(0, -lift);
        if (phase != Phase.DAY) drawSmoke(canvas, seconds);
        if (season == Season.CHRISTMAS) {
            canvas.save();
            scaleTree(canvas);
            drawLights(canvas, seconds);
            canvas.restore();
        }
        if (season == Season.HALLOWEEN) drawCandles(canvas, seconds);
        canvas.restore();
        if (season == Season.HALLOWEEN) drawBats(canvas, seconds);
        if (sky == Season.Fx.RAIN) drawRain(canvas, seconds);
        else if (sky == Season.Fx.SNOW) drawSnow(canvas, seconds);
        canvas.restore();
        if (running) postInvalidateDelayed(FRAME_MS);
    }

    private void drawStars(Canvas c, float s, float strength) {
        for (int i = 0; i < STARS.length; i++) {
            float[] st = STARS[i];
            // Every other star twinkles, each on its own slow period.
            float twinkle = i % 2 == 0 ? 1f : 0.65f + 0.35f * (float) Math.sin(s * (0.9f + i * 0.13f) + i);
            fx.setColor(Color.argb(Math.round(240 * strength * twinkle), 255, 255, 255));
            c.drawCircle(st[0], st[1], st[2], fx);
        }
    }

    private void drawClouds(Canvas c, float s) {
        // Drift 230 px one way over 70 s, then back: the mockup's ease-in-out alternate.
        float t = (float) (0.5 - 0.5 * Math.cos(Math.PI * s / 70.0));
        float dx = -90 + 230 * t;
        fx.setColor(Color.argb(phase == Phase.DAY ? 150 : 120, 255, 255, 255));
        for (float[] cl : CLOUDS) {
            c.drawOval(cl[0] - cl[2] + dx, cl[1] - cl[3], cl[0] + cl[2] + dx, cl[1] + cl[3], fx);
        }
    }

    private void drawSmoke(Canvas c, float s) {
        for (int i = 0; i < 3; i++) {
            float t = ((s + i * 2.33f) % 7f) / 7f;              // 0..1 over a 7 s rise
            float alpha = t < 0.2f ? t / 0.2f * 0.45f : 0.45f * (1 - (t - 0.2f) / 0.8f);
            float r = 10 * (0.6f + 1.1f * t);
            fx.setColor(Color.argb(Math.round(alpha * 255), 0xD8, 0xD2, 0xE2));
            c.drawCircle(1595 + 34 * t, 680 - 130 * t, r, fx);
        }
    }

    private void drawLights(Canvas c, float s) {
        int[] colors = {0xFFFFE27A, 0xFFFF6B6B, 0xFF7AD7FF, 0xFF9CFF8F};
        for (int i = 0; i < LIGHTS.length; i++) {
            float on = 0.45f + 0.55f * (0.5f + 0.5f * (float) Math.sin(s * (1.3f + i * 0.17f) + i * 1.9f));
            fx.setColor((Math.round(on * 255) << 24) | (colors[i % colors.length] & 0xFFFFFF));
            c.drawCircle(LIGHTS[i][0], LIGHTS[i][1], 7, fx);
        }
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
            float span = 28 - i * 4;
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
        rain.setStrokeWidth(2.4f);
        rain.setColor(0x8CC8D8E8);
        for (int i = 0; i < 90; i++) {
            float speed = 1300 + (i * 37) % 500;
            float y = (i * 137 + s * speed) % (H + 60) - 30;
            float x = ((i * 211) % (W + 200)) - (y * 0.12f);
            c.drawLine(x, y, x - 5, y + 36, rain);
        }
    }

    private void drawSnow(Canvas c, float s) {
        for (int i = 0; i < 110; i++) {
            float r = 2.2f + (i % 5) * 0.95f;
            float speed = 42 + r * 19;
            float y = (i * 113 + s * speed) % (H + 20) - 10;
            float x = ((i * 233) % W) + 26 * (float) Math.sin(s * 0.7f + i);
            fx.setColor(Color.argb(150 + (i % 5) * 20, 255, 255, 255));
            c.drawCircle(x, y, r, fx);
        }
    }
}
