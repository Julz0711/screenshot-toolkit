package com.julz.screenshottoolkit.core;

import java.time.Duration;
import java.util.Locale;

/** What the gallery shows. Matching is case-insensitive. A null album means every screenshot. */
public record ShotFilter(boolean favoritesOnly, String world, Period period, String search, String album) {
    public static final ShotFilter ALL = new ShotFilter(false, null, Period.ALL, "", null);

    public enum Period {
        ALL(null),
        TODAY(Duration.ofDays(1)),
        WEEK(Duration.ofDays(7)),
        MONTH(Duration.ofDays(30));

        private final Duration length;

        Period(Duration length) {
            this.length = length;
        }

        public boolean contains(long takenAt, long now) {
            return length == null || now - takenAt <= length.toMillis();
        }

        public Period next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    public boolean matches(Shot shot, long now) {
        if (favoritesOnly && !shot.favorite()) {
            return false;
        }
        if (album != null && !shot.inAlbum(album)) {
            return false;
        }
        if (world != null && !world.equals(shot.world())) {
            return false;
        }
        if (!period.contains(shot.takenAt(), now)) {
            return false;
        }
        if (search == null || search.isBlank()) {
            return true;
        }
        String needle = search.toLowerCase(Locale.ROOT).strip();
        return shot.name().toLowerCase(Locale.ROOT).contains(needle)
                || (shot.world() != null && shot.world().toLowerCase(Locale.ROOT).contains(needle))
                || shot.albums().stream().anyMatch(album -> album.toLowerCase(Locale.ROOT).contains(needle));
    }

    public ShotFilter withFavoritesOnly(boolean value) {
        return new ShotFilter(value, world, period, search, album);
    }

    public ShotFilter withWorld(String value) {
        return new ShotFilter(favoritesOnly, value, period, search, album);
    }

    public ShotFilter withPeriod(Period value) {
        return new ShotFilter(favoritesOnly, world, value, search, album);
    }

    public ShotFilter withSearch(String value) {
        return new ShotFilter(favoritesOnly, world, period, value, album);
    }

    public ShotFilter withAlbum(String value) {
        return new ShotFilter(favoritesOnly, world, period, search, value);
    }
}
