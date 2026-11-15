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

    /**
     * Where the shared folder is. Set on the TV in Personalizza → Condivisione tra le TV; until then
     * the values built in from cosy.properties.
     */
    static final class Config {
        final boolean on;
        final String host, user, password, dir, tvName;

        Config(boolean on, String host, String user, String password, String dir, String tvName) {
            this.on = on;
            this.host = host.trim();
            this.user = user.trim();
            this.password = password;
            String d = dir.trim();
            while (d.endsWith("/")) d = d.substring(0, d.length() - 1);
            this.dir = d.isEmpty() ? "HomeFamiglia" : d;
            this.tvName = tvName.trim();
        }

        boolean usable() {
            return on && !host.isEmpty();
        }

        String hostOnly() {
            int colon = host.lastIndexOf(':');
            if (colon <= 0) return host;
            try {
                Integer.parseInt(host.substring(colon + 1));
                return host.substring(0, colon);
            } catch (NumberFormatException e) {
                return host;
            }
        }

        int port() {
            int colon = host.lastIndexOf(':');
            if (colon <= 0) return 21;
            try {
                return Integer.parseInt(host.substring(colon + 1));
            } catch (NumberFormatException e) {
                return 21;
            }
        }
    }

    static Config config(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String builtHost = ctx.getString(R.string.sync_ftp_host);
        return new Config(p.getBoolean("on", !builtHost.trim().isEmpty()),
                p.getString("host", builtHost),
                p.getString("user", ctx.getString(R.string.sync_ftp_user)),
                p.getString("password", ctx.getString(R.string.sync_ftp_password)),
                p.getString("dir", ctx.getString(R.string.sync_ftp_dir)),
                p.getString("tv_name", ctx.getString(R.string.sync_tv_name)));
    }

    /** Saves the settings page; the next visit to the Home goes to the NAS straight away. */
    static synchronized void save(Context ctx, Config c) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("on", c.on).putString("host", c.host).putString("user", c.user)
                .putString("password", c.password).putString("dir", c.dir).putString("tv_name", c.tvName)
                .remove("sent").remove("status_at").remove("status_error").remove("status_tvs").apply();
        lastVisit = 0;
        remote = Collections.emptyList();
    }

    static boolean enabled(Context ctx) {
        return config(ctx).usable();
    }

    /** How the last visit to the NAS went, for the settings page. */
    static final class Status {
        final long at;
        final String error;
        final List<String> tvs;

        Status(long at, String error, List<String> tvs) {
            this.at = at;
            this.error = error;
            this.tvs = tvs;
        }
    }

    static Status status(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String tvs = p.getString("status_tvs", "");
        return new Status(p.getLong("status_at", 0), p.getString("status_error", null),
                tvs.isEmpty() ? Collections.emptyList() : java.util.Arrays.asList(tvs.split("\n")));
    }

    /**
     * Publishes this TV's titles and returns the other TVs' ones, each tagged with its TV's name.
     * At most every couple of minutes; in between, and when the NAS can't be reached, the last ones.
     */
    static synchronized List<WatchNext.Item> exchange(Context ctx, List<WatchNext.Item> local) {
        Config cfg = config(ctx);
        if (!cfg.usable()) return Collections.emptyList();
        long now = System.currentTimeMillis();
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String ourKey = key(local);
        boolean changed = !ourKey.equals(prefs.getString("sent", null));
        if (!changed && now - lastVisit < MIN_INTERVAL_MS) return remote;
        lastVisit = now;
        try {
            List<String> tvs = new ArrayList<>();
            remote = visit(ctx, cfg, changed ? local : null, now, tvs);
            if (changed) prefs.edit().putString("sent", ourKey).apply();
            record(ctx, now, null, tvs);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "NAS not reachable: " + e.getMessage());
            record(ctx, now, describe(ctx, e), null);
        }
        return remote;
    }

    /**
     * Tries a configuration from the settings page: logs in, makes the folder, writes this TV's file
     * and reads the others'. Returns null when all went well, otherwise what went wrong, in words.
     * Off the main thread.
     */
    static String test(Context ctx, Config cfg, List<String> tvsFound) {
        long now = System.currentTimeMillis();
        try {
            List<WatchNext.Item> found = visit(ctx, cfg, Collections.emptyList(), now, tvsFound);
            synchronized (Sync.class) {
                remote = found;
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("sent").apply();
            return null;
        } catch (IOException | RuntimeException e) {
            return describe(ctx, e);
        }
    }

    /** One visit: our file written when {@code ours} is not null, everyone else's read. */
    private static List<WatchNext.Item> visit(Context ctx, Config cfg, List<WatchNext.Item> ours, long now,
                                              List<String> tvs) throws IOException {
        String mine = fileName(ctx);
        try (Ftp ftp = new Ftp(cfg.hostOnly(), cfg.port(), cfg.user, cfg.password)) {
            ftp.mkdirs(cfg.dir);
            if (ours != null) {
                ftp.put(cfg.dir + "/" + mine, encode(tvName(ctx, cfg), ours, now).getBytes(StandardCharsets.UTF_8));
            }
            List<WatchNext.Item> found = new ArrayList<>();
            for (String name : ftp.list(cfg.dir)) {
                if (!name.startsWith("recent-") || !name.endsWith(".json") || name.equals(mine)) continue;
                byte[] data = ftp.get(cfg.dir + "/" + name);
                if (data == null) continue;
                String json = new String(data, StandardCharsets.UTF_8);
                found.addAll(decode(json, now));
                try {
                    String tv = new JSONObject(json).optString("tv", "");
                    if (!tv.isEmpty() && !tvs.contains(tv)) tvs.add(tv);
                } catch (JSONException ignored) {
                }
            }
            return found;
        }
    }

    private static void record(Context ctx, long at, String error, List<String> tvs) {
        SharedPreferences.Editor e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("status_at", at);
        if (error == null) e.remove("status_error").putString("status_tvs", String.join("\n", tvs));
        else e.putString("status_error", error);
        e.apply();
    }

    /** A failure in words someone in front of the TV can act on. */
    static String describe(Context ctx, Exception e) {
        String m = String.valueOf(e.getMessage());
        if (e instanceof java.net.UnknownHostException) return ctx.getString(R.string.nas_err_host);
        if (e instanceof java.net.ConnectException || e instanceof java.net.SocketTimeoutException
                || e instanceof java.net.NoRouteToHostException) return ctx.getString(R.string.nas_err_connect);
        if (m.startsWith("FTP 530")) return ctx.getString(R.string.nas_err_login);
        if (m.startsWith("FTP 55")) return ctx.getString(R.string.nas_err_folder);
        return ctx.getString(R.string.nas_err_other, m);
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
        if (id == null || id.isEmpty()) {
            // No Android id: a random one of our own, kept, so two such TVs never share a file.
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            id = p.getString("own_id", null);
            if (id == null) {
                id = java.util.UUID.randomUUID().toString();
                p.edit().putString("own_id", id).apply();
            }
        }
        return "recent-" + id.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT) + ".json";
    }

    /** The name chosen for this TV, else the one it goes by in its settings ("Philips Salotto"), else its model. */
    static String tvName(Context ctx, Config cfg) {
        if (!cfg.tvName.isEmpty()) return cfg.tvName;
        return deviceName(ctx);
    }

    static String deviceName(Context ctx) {
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
