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
import android.view.animation.OvershootInterpolator;
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
        final float lift = 10 * d, radius = 9 * d;
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
        load(item.poster, poster, (int) (130 * d));

        View shade = new View(ctx);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xE6000000, 0x00000000}));
        addView(shade, new LayoutParams(LayoutParams.MATCH_PARENT, (int) (34 * d), Gravity.BOTTOM));

        TextView title = new TextView(ctx);
        title.setText(item.detail.isEmpty() ? item.title : item.title + " · " + item.detail);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(10f);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setTypeface(getResources().getFont(R.font.outfit));
        int pad = (int) (5 * d);
        title.setPadding(pad, 0, pad, (int) (5 * d));
        addView(title, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        if (item.progress > 0) {
            View bar = new View(ctx) {
                @Override
                protected void onMeasure(int w, int h) {
                    int full = MeasureSpec.getSize(w);
                    setMeasuredDimension(Math.round(full * item.progress), MeasureSpec.getSize(h));
                }
            };
            bar.setBackgroundColor(0xFFFFFFFF);
            addView(bar, new LayoutParams(LayoutParams.MATCH_PARENT, (int) (2 * d), Gravity.BOTTOM));
        }

        setOnClickListener(v -> action.run());
        setOnFocusChangeListener((v, focused) -> {
            animate().cancel();
            animate().scaleX(focused ? Tile.FOCUS_SCALE : 1f).scaleY(focused ? Tile.FOCUS_SCALE : 1f)
                    .alpha(focused ? 1f : restAlpha)
                    .translationZ(focused ? lift : 0f)
                    .setDuration(focused ? 240 : 160)
                    .setInterpolator(new OvershootInterpolator(focused ? 1.4f : 0f))
                    .start();
        });
    }

    /** Web posters only; a missing one leaves the plain tile with its title. */
    private static void load(String url, ImageView into, int maxWidth) {
        if (url == null || !url.startsWith("http")) return;
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
