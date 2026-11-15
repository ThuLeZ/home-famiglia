package com.cosyhome.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * What kind of land is around the TV, so the scene can show it: worked out once from the height of
 * the ground at the configured place and at 24 points around it (2.5, 6 and 12 km away), one request
 * to Open-Meteo's elevation service, the same one the weather comes from. Kept until the place changes.
 *
 * The heights tell it apart: the sea reads exactly 0; a lake is one height repeated over several points
 * (its flat surface) among higher ground; mountains rise high or steeply; a plain hardly changes at all.
 */
final class Terrain {

    private static final String TAG = "FamigliaTerrain";
    private static final String PREFS = "terrain";
    private static final float[] RINGS_KM = {2.5f, 6f, 12f};
    /** After a failure, how long before trying again: the service limits how often it is asked. */
    private static final long RETRY_MS = 3 * 3600_000L;

    private Terrain() {
    }

    /** "auto", or the name of a kind chosen in Personalizza. */
    static String choice(Context ctx) {
        return prefs(ctx).getString("choice", "auto");
    }

    static void choose(Context ctx, String choice) {
        prefs(ctx).edit().putString("choice", choice).apply();
    }

    /** The land to show: the one chosen, else the one found for this place, else hills. */
    static Land.Kind current(Context ctx, double lat, double lon, boolean located) {
        String choice = choice(ctx);
        if (!"auto".equals(choice)) {
            try {
                return Land.Kind.valueOf(choice);
            } catch (IllegalArgumentException ignored) {
                // An old value: fall back to finding it.
            }
        }
        Land.Kind found = located ? found(ctx, lat, lon) : null;
        return found != null ? found : Land.Kind.HILLS;
    }

    /** What was found for this place, or null if it hasn't been yet. */
    static Land.Kind found(Context ctx, double lat, double lon) {
        SharedPreferences p = prefs(ctx);
        if (!key(lat, lon).equals(p.getString("key", null))) return null;
        try {
            return Land.Kind.valueOf(p.getString("kind", ""));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Whether to ask now: in automatic mode, not yet known for this place, and not just failed. */
    static boolean due(Context ctx, double lat, double lon) {
        return "auto".equals(choice(ctx)) && found(ctx, lat, lon) == null
                && System.currentTimeMillis() >= prefs(ctx).getLong("retry_at", 0);
    }

    /** Finds the land on a background thread; {@code done} runs on the main thread, only on success. */
    static void find(Context ctx, double lat, double lon, Consumer<Land.Kind> done) {
        Context app = ctx.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                Land.Kind kind = classify(heights(lat, lon));
                prefs(app).edit().putString("key", key(lat, lon)).putString("kind", kind.name()).remove("retry_at").apply();
                Log.i(TAG, "land around " + key(lat, lon) + ": " + kind);
                main.post(() -> done.accept(kind));
            } catch (Exception e) {
                Log.w(TAG, "elevation not available: " + e);
                prefs(app).edit().putLong("retry_at", System.currentTimeMillis() + RETRY_MS).apply();
            }
        }, "terrain").start();
    }

    /** The ground's height at the place (first) and at the 24 points around it. */
    private static double[] heights(double lat, double lon) throws Exception {
        StringBuilder lats = new StringBuilder(), lons = new StringBuilder();
        append(lats, lons, lat, lon);
        for (float km : RINGS_KM) {
            for (int k = 0; k < 8; k++) {
                double a = k * Math.PI / 4;
                append(lats, lons, lat + km / 111.0 * Math.cos(a),
                        lon + km / (111.0 * Math.cos(Math.toRadians(lat))) * Math.sin(a));
            }
        }
        URL url = new URL("https://api.open-meteo.com/v1/elevation?latitude=" + lats + "&longitude=" + lons);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            if (conn.getResponseCode() != 200) throw new java.io.IOException("HTTP " + conn.getResponseCode());
            JSONArray e = new JSONObject(read(conn.getInputStream())).getJSONArray("elevation");
            double[] out = new double[e.length()];
            for (int i = 0; i < out.length; i++) out[i] = e.getDouble(i);
            if (out.length != 1 + 8 * RINGS_KM.length) throw new java.io.IOException("unexpected reply");
            return out;
        } finally {
            conn.disconnect();
        }
    }

    private static void append(StringBuilder lats, StringBuilder lons, double lat, double lon) {
        if (lats.length() > 0) {
            lats.append(',');
            lons.append(',');
        }
        lats.append(String.format(Locale.ROOT, "%.4f", lat));
        lons.append(String.format(Locale.ROOT, "%.4f", lon));
    }

    /**
     * The land from the heights: the place's own first, then the points around it. Tried on real
     * places (see TerrainTest): Genova and Rimini are sea, Bellagio and Salò a lake, Bolzano, Cortina
     * and Lecco mountains, Milano a plain, Siena, Firenze and Roma hills.
     */
    static Land.Kind classify(double[] h) {
        double centre = h[0], min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        int sea = 0;
        Map<Long, Integer> same = new HashMap<>();
        int mostSame = 0;
        for (int i = 0; i < h.length; i++) {
            min = Math.min(min, h[i]);
            max = Math.max(max, h[i]);
            if (i == 0) continue;
            if (h[i] <= 0.5) {
                sea++;
            } else {
                long k = Math.round(h[i] * 2);   // to half a metre
                int n = same.merge(k, 1, Integer::sum);
                mostSame = Math.max(mostSame, n);
            }
        }
        double relief = max - min;
        if (sea >= 2 && centre < 500) return Land.Kind.SEA;
        // A lake's surface is perfectly flat: the same height at four or more points, in uneven land.
        if (mostSame >= 4 && relief >= 100) return Land.Kind.LAKE;
        if (centre >= 700 || relief >= 900) return Land.Kind.MOUNTAINS;
        if (relief < 100 && centre < 300) return Land.Kind.PLAINS;
        return Land.Kind.HILLS;
    }

    private static String key(double lat, double lon) {
        return String.format(Locale.ROOT, "%.2f,%.2f", lat, lon);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String read(InputStream in) throws java.io.IOException {
        try (InputStream is = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            for (int n; (n = is.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }
}
