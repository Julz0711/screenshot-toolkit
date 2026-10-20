package com.julz.screenshottoolkit.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ShotFilterTest {
    private static final long NOW = 1_800_000_000_000L;

    private static Shot shot(String name, String world, boolean favorite, long age) {
        ShotMetadata metadata = new ShotMetadata();
        metadata.world = world;
        metadata.favorite = favorite;
        return new Shot(Path.of(name), NOW - age, 1, metadata);
    }

    @Test
    void allMatchesEverything() {
        assertTrue(ShotFilter.ALL.matches(new Shot(Path.of("a.png"), 0, 1, null), NOW));
    }

    @Test
    void favoritesOnly() {
        ShotFilter filter = ShotFilter.ALL.withFavoritesOnly(true);
        assertTrue(filter.matches(shot("a.png", null, true, 0), NOW));
        assertFalse(filter.matches(shot("a.png", null, false, 0), NOW));
        assertFalse(filter.matches(new Shot(Path.of("a.png"), NOW, 1, null), NOW));
    }

    @Test
    void world() {
        ShotFilter filter = ShotFilter.ALL.withWorld("Survival");
        assertTrue(filter.matches(shot("a.png", "Survival", false, 0), NOW));
        assertFalse(filter.matches(shot("a.png", "Creative", false, 0), NOW));
        assertFalse(filter.matches(shot("a.png", null, false, 0), NOW));
    }

    @Test
    void period() {
        ShotFilter week = ShotFilter.ALL.withPeriod(ShotFilter.Period.WEEK);
        assertTrue(week.matches(shot("a.png", null, false, Duration.ofDays(6).toMillis()), NOW));
        assertFalse(week.matches(shot("a.png", null, false, Duration.ofDays(8).toMillis()), NOW));
        assertEquals(ShotFilter.Period.ALL, ShotFilter.Period.MONTH.next());
    }

    @Test
    void searchMatchesNameAndWorldIgnoringCase() {
        ShotFilter filter = ShotFilter.ALL.withSearch("  castle ");
        assertTrue(filter.matches(shot("2026_castle.png", null, false, 0), NOW));
        assertTrue(filter.matches(shot("a.png", "My Castle", false, 0), NOW));
        assertFalse(filter.matches(shot("a.png", "Farm", false, 0), NOW));
    }

    @Test
    void customNameOnlyForNamesThePlayerGave() {
        assertNull(new Shot(java.nio.file.Path.of("2026-10-20_12.36.34.png"), 0, 0, null).customName());
        assertNull(new Shot(java.nio.file.Path.of("2026-10-20_12.36.34_2_crop_1.png"), 0, 0, null).customName());
        assertEquals("Mountain base", new Shot(java.nio.file.Path.of("Mountain base.png"), 0, 0, null).customName());
    }
}
