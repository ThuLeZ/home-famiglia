package com.cosyhome.tv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.robolectric.Shadows.shadowOf;

/**
 * Made-up apps, recent titles and channels for the previews, so the rows look as they do on a real TV.
 * The artwork is plain gradients with a name on them, not anyone's logo.
 */
final class PreviewData {

    private static final String[][] APPS = {
            // package, label, banner colours
            {"com.netflix.ninja", "Netflix", "#2B0A0C", "#B81D24"},
            {"com.amazon.amazonvideo.livingroom", "Prime Video", "#0B1B2B", "#1F7FC4"},
            {"com.disney.disneyplus", "Disney+", "#0A1240", "#2F55B7"},
            {"com.google.android.youtube.tv", "YouTube", "#1A1A1A", "#D7301F"},
            {"com.spotify.tv.android", "Spotify", "#0F1A12", "#1E9C4B"},
            {"it.rai.raiplay", "RaiPlay", "#04213F", "#0A62A8"},
            {"it.mediaset.infinity", "Infinity", "#140B30", "#6A3BD1"},
            {"com.dazn", "DAZN", "#101010", "#3A3A3A"},
            {"com.plexapp.android", "Plex", "#1B1B1B", "#C88A12"},
            {"com.android.tv.settings", "Impostazioni", "#202833", "#46586E"},
            {"org.droidtv.playtv", "Guarda TV", "#202833", "#46586E"},
    };

    private PreviewData() {
    }

    static void install(Context ctx) throws Exception {
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Field cacheField = Apps.class.getDeclaredField("CACHE");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String, Object> cache = (Map<String, Object>) cacheField.get(null);
        Class<?> cached = Class.forName("com.cosyhome.tv.Apps$Cached");
        Constructor<?> make = cached.getDeclaredConstructor(long.class, Drawable.class, Drawable.class);
        make.setAccessible(true);
        for (String[] a : APPS) {
            ResolveInfo ri = new ResolveInfo();
            ri.activityInfo = new ActivityInfo();
            ri.activityInfo.packageName = a[0];
            ri.activityInfo.name = a[0] + ".Main";
            ri.activityInfo.applicationInfo = new ApplicationInfo();
            ri.activityInfo.applicationInfo.packageName = a[0];
            ri.nonLocalizedLabel = a[1];
            shadowOf(ctx.getPackageManager()).addResolveInfoForIntent(probe, ri);
            cache.put(a[0], make.newInstance(0L, banner(ctx, a[1], a[2], a[3]), null));
        }

        set(HomeActivity.class, "channels", Arrays.asList(
                new Channels.Channel(1, "1", "Rai 1"), new Channels.Channel(2, "2", "Rai 2"),
                new Channels.Channel(3, "3", "Rai 3"), new Channels.Channel(4, "4", "Rete 4"),
                new Channels.Channel(5, "5", "Canale 5"), new Channels.Channel(6, "7", "La7")));

        long now = System.currentTimeMillis();
        String[][] recent = {
                {"com.netflix.ninja", "La casa sul lago", "S2 E4", "#1F3A4A", "#C9925A", "0.42"},
                {"it.rai.raiplay", "Il commissario", "S1 E7", "#3A2A1F", "#E3B66B", "0.7"},
                {"com.disney.disneyplus", "Viaggio fra le stelle", "", "#0B1A3A", "#6A8FE0", "0.18"},
                {"com.amazon.amazonvideo.livingroom", "Cucina di famiglia", "S3 E1", "#2A3A1F", "#B8C76A", "0.55"},
                {"com.google.android.youtube.tv", "Passeggiata in Toscana", "", "#30402A", "#E8D7A0", "-1"},
                {"com.netflix.ninja", "Il grande nord", "", "#1A2430", "#A7C3D9", "0.9"},
        };
        Field posters = PosterTile.class.getDeclaredField("CACHE");
        posters.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String, Bitmap> posterCache = (Map<String, Bitmap>) posters.get(null);
        List<WatchNext.Item> items = new ArrayList<>();
        for (int i = 0; i < recent.length; i++) {
            String[] r = recent[i];
            String url = "https://preview.invalid/poster" + i + ".png";
            posterCache.put(url, poster(r[3], r[4], i));
            // The second title was watched on another TV of the house.
            items.add(new WatchNext.Item(r[0], r[1], r[2], url, null, now - i * 3_600_000L, Float.parseFloat(r[5]),
                    i == 1 ? "Camera" : null));
        }
        set(HomeActivity.class, "recent", items);
    }

    private static void set(Class<?> c, String name, Object value) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, value);
    }

    private static Drawable banner(Context ctx, String name, String dark, String light) {
        int w = 320, h = 180;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, h, w, 0, android.graphics.Color.parseColor(dark),
                android.graphics.Color.parseColor(light), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
        p.setColor(0xFFFFFFFF);
        p.setTextSize(40);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText(name, w / 2f, h / 2f + 14, p);
        return new BitmapDrawable(ctx.getResources(), b);
    }

    /** An abstract "film still": a sky, a sun and a horizon in two colours. */
    private static Bitmap poster(String dark, String light, int seed) {
        int w = 320, h = 180;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int d = android.graphics.Color.parseColor(dark), l = android.graphics.Color.parseColor(light);
        p.setShader(new LinearGradient(0, 0, 0, h, l, d, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(new RadialGradient(80 + seed * 40, 70, 60, 0xCCFFF4DA, 0x00FFF4DA, Shader.TileMode.CLAMP));
        c.drawCircle(80 + seed * 40, 70, 60, p);
        p.setShader(null);
        p.setColor(d);
        c.drawOval(-60, 120 - seed * 4, 220, 260, p);
        c.drawOval(140, 110 + seed * 3, 420, 260, p);
        return b;
    }
}
