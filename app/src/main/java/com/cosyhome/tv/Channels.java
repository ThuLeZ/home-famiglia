package com.cosyhome.tv;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

/**
 * The first channels of the TV's own list. Philips keeps it in the provider behind its TV guide,
 * which any app can read; Android's own channel table comes back empty on these sets.
 */
final class Channels {

    static final class Channel {
        final long id;
        final String number, name;

        Channel(long id, String number, String name) {
            this.id = id;
            this.number = number;
            this.name = name;
        }
    }

    private static final String[] SOURCES = {"antenna", "cable", "satellite"};

    private Channels() {
    }

    /** Off the main thread: the provider can take a moment to start. Empty on any other TV. */
    static List<Channel> first(Context ctx, int count) {
        List<Channel> out = new ArrayList<>();
        for (String source : SOURCES) {
            Uri uri = Uri.parse("content://org.droidtv.epgdvb/" + source + "/indextable");
            try (Cursor c = ctx.getContentResolver().query(uri, null,
                    "presetnumber>0 AND browsable=1", null, "presetnumber")) {
                if (c == null) continue;
                int id = c.getColumnIndex("presetid"), num = c.getColumnIndex("presetnumber"),
                        name = c.getColumnIndex("channelname");
                if (id < 0 || num < 0 || name < 0) continue;
                while (c.moveToNext() && out.size() < count) {
                    int preset = c.getInt(num);
                    // The list numbers channels 100, 200, 300 for 1, 2, 3.
                    String shown = String.valueOf(preset % 100 == 0 ? preset / 100 : preset);
                    out.add(new Channel(c.getLong(id), shown, c.getString(name)));
                }
            } catch (RuntimeException ignored) {
                // No such provider or table on this TV.
            }
            if (!out.isEmpty()) break;
        }
        return out;
    }
}
