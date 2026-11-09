package com.cosyhome.tv;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Something you were watching: its poster, its title, and how far through you are. */
@SuppressLint("ViewConstructor")
final class PosterTile extends FrameLayout {

    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    /** Small enough to keep: six posters at tile size. Cleared when the row changes. */
    private static final Map<String, Bitmap> CACHE = new HashMap<>();

    PosterTile(Context ctx, WatchNext.Item item, float restAlpha, Runnable action) {
        super(ctx);
        float d = getResources().getDisplayMetrics().density;
        final float lift = 8 * d, radius = 12 * d;
        setFocusable(true);
        setClickable(true);
        setBackgroundColor(0xFF26303C);
        setForeground(ctx.getDrawable(R.drawable.tile_ring));
        setAlpha(restAlpha);
        setContentDescription(item.title);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        setClipToOutline(true);

        ImageView poster = new ImageView(ctx);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        addView(poster, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        load(item.poster, poster, (int) (150 * d));

        View shade = new View(ctx);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xE6000000, 0x8C000000, 0x00000000}));
        addView(shade, new LayoutParams(LayoutParams.MATCH_PARENT, (int) (50 * d), Gravity.BOTTOM));

        // The title, and under it the episode and, for a title from another TV, where it was watched.
        LinearLayout text = new LinearLayout(ctx);
        text.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(8 * d);
        text.setPadding(pad, 0, pad, Math.round((item.progress > 0 ? 12 : 7) * d));
        TextView title = line(item.title, 11.5f, 600, 0xFFFFFFFF);
        text.addView(title);
        String sub = item.detail;
        if (item.from != null && !item.from.isEmpty()) {
            String where = ctx.getString(R.string.watched_on, item.from);
            sub = sub.isEmpty() ? where : sub + " · " + where;
        }
        if (!sub.isEmpty()) text.addView(line(sub, 10f, 400, 0xC7FFFFFF));
        addView(text, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        if (item.progress > 0) {
            // How far through: a thin rounded bar on a faint track, inset from the edges.
            FrameLayout track = new FrameLayout(ctx);
            GradientDrawable trackBg = new GradientDrawable();
            trackBg.setColor(0x47FFFFFF);
            trackBg.setCornerRadius(2 * d);
            track.setBackground(trackBg);
            View bar = new View(ctx) {
                @Override
                protected void onMeasure(int w, int h) {
                    int full = MeasureSpec.getSize(w);
                    setMeasuredDimension(Math.max(Math.round(3 * d), Math.round(full * item.progress)),
                            MeasureSpec.getSize(h));
                }
            };
            GradientDrawable barBg = new GradientDrawable();
            barBg.setColor(0xFFFFFFFF);
            barBg.setCornerRadius(2 * d);
            bar.setBackground(barBg);
            track.addView(bar, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            LayoutParams tlp = new LayoutParams(LayoutParams.MATCH_PARENT, Math.round(3 * d), Gravity.BOTTOM);
            tlp.setMargins(pad, 0, pad, Math.round(6 * d));
            addView(track, tlp);
        }

        setOnClickListener(v -> action.run());
        setOnFocusChangeListener((v, focused) -> {
            animate().cancel();
            animate().scaleX(focused ? Tile.FOCUS_SCALE : 1f).scaleY(focused ? Tile.FOCUS_SCALE : 1f)
                    .alpha(focused ? 1f : restAlpha)
                    .translationZ(focused ? lift : 0f)
                    .setDuration(focused ? 200 : 160)
                    .setInterpolator(new DecelerateInterpolator(2f))
                    .start();
        });
    }

    private TextView line(String s, float sp, int weight, int color) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(sp);
        t.setSingleLine(true);
        t.setIncludeFontPadding(false);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setTypeface(getResources().getFont(R.font.outfit));
        t.setFontVariationSettings("'wght' " + weight);
        t.setShadowLayer(4f, 0f, 1f, 0x80000000);
        return t;
    }

    /**
     * Web posters only; a missing one leaves the plain tile with its title. Android blocks plain
     * http, so those are asked for over https, which the image servers of the big apps all offer.
     */
    private static void load(String link, ImageView into, int maxWidth) {
        if (link == null) return;
        final String url = link.regionMatches(true, 0, "http://", 0, 7) ? "https://" + link.substring(7) : link;
        if (!url.regionMatches(true, 0, "https://", 0, 8)) return;
        Bitmap cached;
        synchronized (CACHE) {
            cached = CACHE.get(url);
        }
        if (cached != null) {
            into.setImageBitmap(cached);
            return;
        }
        Handler main = new Handler(Looper.getMainLooper());
        LOADER.execute(() -> {
            Bitmap b = fetch(url, maxWidth);
            if (b == null) return;
            synchronized (CACHE) {
                if (CACHE.size() > 24) CACHE.clear();
                CACHE.put(url, b);
            }
            main.post(() -> into.setImageBitmap(b));
        });
    }

    private static Bitmap fetch(String url, int maxWidth) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            if (conn.getResponseCode() != 200) return null;
            byte[] data;
            try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[16384];
                int n, total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_BYTES) return null;
                    out.write(buf, 0, n);
                }
                data = out.toByteArray();
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            o.inJustDecodeBounds = false;
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= maxWidth) o.inSampleSize *= 2;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
