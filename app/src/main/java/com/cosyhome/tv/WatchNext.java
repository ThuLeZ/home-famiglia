package com.cosyhome.tv;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.tv.TvContract;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What the apps say you were watching, from the system's Watch Next table. Needs READ_TV_LISTINGS,
 * granted over ADB; the query must have no selection. Method from SferaDev's cleanyourtv fork (MIT).
 */
final class WatchNext {

    static final class Item {
        final String pkg, title, detail, poster, intentUri;
        final long engaged;
        final float progress;

        Item(String pkg, String title, String detail, String poster, String intentUri, long engaged, float progress) {
            this.pkg = pkg;
            this.title = title;
            this.detail = detail;
            this.poster = poster;
            this.intentUri = intentUri;
            this.engaged = engaged;
            this.progress = progress;
        }
    }

    private static final int TYPE_WATCHLIST = 3;
    private static final long MAX_AGE_MS = 365L * 24 * 3600_000L;
    private static final String[] COLUMNS = {"package_name", "title", "episode_title", "season_display_number",
            "episode_display_number", "poster_art_uri", "thumbnail_uri", "intent_uri", "watch_next_type",
            "last_engagement_time_utc_millis", "last_playback_position_millis", "duration_millis"};

    private WatchNext() {
    }

    /** Newest first. Off the main thread. Skips finished things, wish lists and apps you no longer have. */
    static List<Item> latest(Context ctx, Set<String> launchable, int count) {
        List<Item> all = new ArrayList<>();
        long now = System.currentTimeMillis();
        try (Cursor c = ctx.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, COLUMNS, null, null, null)) {
            while (c != null && c.moveToNext()) {
                String pkg = c.getString(0), title = c.getString(1);
                long engaged = c.getLong(9), position = c.getLong(10), duration = c.getLong(11);
                if (pkg == null || title == null || title.trim().isEmpty() || !launchable.contains(pkg)) continue;
                if (c.getInt(8) == TYPE_WATCHLIST || now - engaged > MAX_AGE_MS) continue;
                float progress = duration > 0 && position > 0 ? Math.min(1f, (float) position / duration) : -1f;
                if (progress > 0.95f) continue;
                String poster = c.getString(5) != null ? c.getString(5) : c.getString(6);
                all.add(new Item(pkg, title.trim(), detail(c.getString(2), c.getString(3), c.getString(4)),
                        poster, c.getString(7), engaged, progress));
            }
        } catch (RuntimeException ignored) {
            // No permission, or no such table on this TV: no row.
        }
        Collections.sort(all, (a, b) -> Long.compare(b.engaged, a.engaged));
        List<Item> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Item i : all) {
            if (out.size() < count && seen.add(i.pkg + "/" + i.title)) out.add(i);
        }
        return out;
    }

    private static String detail(String episodeTitle, String season, String episode) {
        StringBuilder b = new StringBuilder();
        if (season != null && !season.trim().isEmpty() && season.trim().length() < 4) b.append("S").append(season.trim());
        if (episode != null && !episode.trim().isEmpty()) b.append(b.length() > 0 ? " " : "").append("E").append(episode.trim());
        if (b.length() == 0 && episodeTitle != null) b.append(episodeTitle.trim());
        return b.toString();
    }

    /** The publishing app's own link to the title, kept inside that app; null when it cannot be read. */
    static Intent intent(Item item) {
        if (item.intentUri == null || item.intentUri.trim().isEmpty()) return null;
        try {
            Intent i = Intent.parseUri(item.intentUri, Intent.URI_INTENT_SCHEME);
            if (i.getPackage() == null) i.setPackage(item.pkg);
            if (!item.pkg.equals(i.getPackage())) return null;
            i.setSelector(null);
            return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
