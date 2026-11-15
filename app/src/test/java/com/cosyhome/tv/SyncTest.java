package com.cosyhome.tv;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The shared file's format, and how another TV's titles join this one's. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SyncTest {

    private static final long NOW = 1_800_000_000_000L;

    @Test
    public void fileRoundTrip() {
        List<WatchNext.Item> items = Collections.singletonList(
                new WatchNext.Item("com.netflix.ninja", "La casa sul lago", "S2 E4", "https://x/p.jpg", "intent:#Intent;end", NOW - 1000, 0.42f));
        List<WatchNext.Item> back = Sync.decode(Sync.encode("Camera", items, NOW), NOW);
        assertEquals(1, back.size());
        WatchNext.Item i = back.get(0);
        assertEquals("La casa sul lago", i.title);
        assertEquals("S2 E4", i.detail);
        assertEquals("Camera", i.from);
        assertEquals("intent:#Intent;end", i.intentUri);
        assertEquals(0.42f, i.progress, 0.001f);
    }

    @Test
    public void titlesFromOtherTvsAreNotSentOnAgain() {
        List<WatchNext.Item> items = Arrays.asList(
                new WatchNext.Item("a", "Mine", "", null, null, NOW, 0.5f),
                new WatchNext.Item("b", "Theirs", "", null, null, NOW, 0.5f, "Salotto"));
        List<WatchNext.Item> back = Sync.decode(Sync.encode("Camera", items, NOW), NOW);
        assertEquals(1, back.size());
        assertEquals("Mine", back.get(0).title);
    }

    @Test
    public void staleOrBrokenFilesAreIgnored() {
        String old = Sync.encode("Cantina", Collections.singletonList(
                new WatchNext.Item("a", "Old", "", null, null, 0, 0.5f)), NOW - 90L * 24 * 3600_000L);
        assertTrue(Sync.decode(old, NOW).isEmpty());
        assertTrue(Sync.decode("not json", NOW).isEmpty());
    }

    @Test
    public void newestViewingWinsAndMissingAppsAreSkipped() {
        List<WatchNext.Item> here = Collections.singletonList(
                new WatchNext.Item("com.netflix.ninja", "La casa sul lago", "S2 E4", null, null, NOW - 1000, 0.42f));
        List<WatchNext.Item> there = Arrays.asList(
                new WatchNext.Item("com.netflix.ninja", "La Casa sul Lago", "S2 E5", null, null, NOW - 10, 0.1f, "Camera"),
                new WatchNext.Item("it.rai.raiplay", "Il commissario", "", null, null, NOW, 0.7f, "Camera"));
        List<WatchNext.Item> merged = Sync.merge(here, there, new HashSet<>(Collections.singletonList("com.netflix.ninja")), 6);
        assertEquals(1, merged.size());
        assertEquals("S2 E5", merged.get(0).detail);
        assertEquals("Camera", merged.get(0).from);
        List<WatchNext.Item> alone = Sync.merge(here, Collections.emptyList(), new HashSet<>(), 6);
        assertNull(alone.get(0).from);
    }
}
