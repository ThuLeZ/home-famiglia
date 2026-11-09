package com.cosyhome.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Shares "Ultimi contenuti" between the TVs of the house through a folder on the home NAS (the
 * FRITZ!Box's, over FTP). Each TV writes only its own file, recent-&lt;id&gt;.json, and reads everyone
 * else's: no two TVs ever write the same file, so there is nothing to lock or merge on the NAS.
 *
 * Off unless cosy.properties sets sync.ftp_host. Runs on a background thread.
 */
final class Sync {

    private static final String TAG = "FamigliaSync";
    private static final String PREFS = "sync";
    /** A TV that hasn't written in this long is ignored: probably unplugged or gone. */
    private static final long MAX_AGE_MS = 60L * 24 * 3600_000L;
    /** Between two visits to the NAS; Home is resumed far more often than that matters. */
    private static final long MIN_INTERVAL_MS = 2 * 60_000L;

    private static List<WatchNext.Item> remote = Collections.emptyList();
    private static long lastVisit;

    private Sync() {
    }

    /** What the other TVs said at the last visit to the NAS, without going there. */
    static synchronized List<WatchNext.Item> cached() {
        return remote;
    }

    static boolean enabled(Context ctx) {
        return !ctx.getString(R.string.sync_ftp_host).trim().isEmpty();
    }

    /**
     * Publishes this TV's titles and returns the other TVs' ones, each tagged with its TV's name.
     * At most every couple of minutes; in between, and when the NAS can't be reached, the last ones.
     */
    static synchronized List<WatchNext.Item> exchange(Context ctx, List<WatchNext.Item> local) {
        if (!enabled(ctx)) return Collections.emptyList();
        long now = System.currentTimeMillis();
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String mine = fileName(ctx);
        byte[] ours = encode(tvName(ctx), local, now).getBytes(StandardCharsets.UTF_8);
        String ourKey = key(local);
        boolean changed = !ourKey.equals(prefs.getString("sent", null));
        if (!changed && now - lastVisit < MIN_INTERVAL_MS) return remote;
        lastVisit = now;

        String host = ctx.getString(R.string.sync_ftp_host).trim();
        int port = 21;
        int colon = host.lastIndexOf(':');
        if (colon > 0) {
            try {
                port = Integer.parseInt(host.substring(colon + 1));
                host = host.substring(0, colon);
            } catch (NumberFormatException ignored) {
            }
        }
        String dir = ctx.getString(R.string.sync_ftp_dir).trim();
        if (dir.isEmpty()) dir = "HomeFamiglia";
        while (dir.endsWith("/")) dir = dir.substring(0, dir.length() - 1);

        try (Ftp ftp = new Ftp(host, port, ctx.getString(R.string.sync_ftp_user), ctx.getString(R.string.sync_ftp_password))) {
            ftp.mkdirs(dir);
            if (changed) {
                ftp.put(dir + "/" + mine, ours);
                prefs.edit().putString("sent", ourKey).apply();
            }
            List<WatchNext.Item> found = new ArrayList<>();
            for (String name : ftp.list(dir)) {
                if (!name.startsWith("recent-") || !name.endsWith(".json") || name.equals(mine)) continue;
                byte[] data = ftp.get(dir + "/" + name);
                if (data != null) found.addAll(decode(new String(data, StandardCharsets.UTF_8), now));
            }
            remote = found;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "NAS not reachable: " + e.getMessage());
        }
        return remote;
    }

    /** Other TVs' titles for apps this TV has too, merged with its own: the newest viewing wins. */
    static List<WatchNext.Item> merge(List<WatchNext.Item> local, List<WatchNext.Item> others,
                                      Set<String> launchable, int count) {
        List<WatchNext.Item> all = new ArrayList<>(local);
        for (WatchNext.Item i : others) if (launchable.contains(i.pkg)) all.add(i);
        return WatchNext.newest(all, count);
    }

    // ---- the file ---------------------------------------------------------------------------------

    /** This TV's own file: named by its Android id, which stays the same across reinstalls of the app. */
    private static String fileName(Context ctx) {
        String id = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (id == null || id.isEmpty()) id = Build.SERIAL;
        return "recent-" + id.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT) + ".json";
    }

    /** The name the TV goes by in its settings ("Philips Salotto"), else its model. */
    static String tvName(Context ctx) {
        String given = ctx.getString(R.string.sync_tv_name).trim();
        if (!given.isEmpty()) return given;
        String name = Settings.Global.getString(ctx.getContentResolver(), "device_name");
        return name == null || name.trim().isEmpty() ? Build.MODEL : name.trim();
    }

    private static String key(List<WatchNext.Item> items) {
        StringBuilder b = new StringBuilder();
        for (WatchNext.Item i : items) {
            b.append(i.pkg).append('|').append(i.title).append('|').append(i.engaged).append('|')
                    .append(Math.round(i.progress * 100)).append(';');
        }
        return b.toString();
    }

    /**
     * {"version":1,"tv":"Salotto","updated":1731234567890,"items":[{"pkg":"com.netflix.ninja",
     * "title":"…","detail":"S2 E4","poster":"https://…","intent":"intent:…","engaged":…,"progress":0.42}]}
     */
    static String encode(String tv, List<WatchNext.Item> items, long now) {
        try {
            JSONArray list = new JSONArray();
            for (WatchNext.Item i : items) {
                if (i.from != null) continue;   // only what was watched here
                list.put(new JSONObject().put("pkg", i.pkg).put("title", i.title).put("detail", i.detail)
                        .put("poster", i.poster == null ? "" : i.poster)
                        .put("intent", i.intentUri == null ? "" : i.intentUri)
                        .put("engaged", i.engaged).put("progress", (double) i.progress));
            }
            return new JSONObject().put("version", 1).put("tv", tv).put("updated", now).put("items", list).toString(1);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    static List<WatchNext.Item> decode(String json, long now) {
        List<WatchNext.Item> out = new ArrayList<>();
        try {
            JSONObject o = new JSONObject(json);
            if (now - o.optLong("updated", 0) > MAX_AGE_MS) return out;
            String tv = o.optString("tv", "");
            JSONArray list = o.optJSONArray("items");
            if (list == null) return out;
            for (int k = 0; k < list.length(); k++) {
                JSONObject i = list.optJSONObject(k);
                if (i == null) continue;
                String pkg = i.optString("pkg", ""), title = i.optString("title", "");
                if (pkg.isEmpty() || title.isEmpty()) continue;
                String poster = i.optString("poster", ""), intent = i.optString("intent", "");
                out.add(new WatchNext.Item(pkg, title, i.optString("detail", ""), poster.isEmpty() ? null : poster,
                        intent.isEmpty() ? null : intent, i.optLong("engaged", 0),
                        (float) i.optDouble("progress", -1), tv.isEmpty() ? "?" : tv));
            }
        } catch (JSONException e) {
            Log.w(TAG, "unreadable file on the NAS: " + e.getMessage());
        }
        return out;
    }
}
