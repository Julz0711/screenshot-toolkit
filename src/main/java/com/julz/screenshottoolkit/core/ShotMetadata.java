package com.julz.screenshottoolkit.core;

import java.util.Locale;

/**
 * What was going on when a screenshot was taken. Stored as a JSON sidecar in screenshots/.meta,
 * never inside the PNG, so other tools see an ordinary image. Every field may be missing:
 * screenshots taken before the mod was installed only have a sidecar once they are starred.
 */
public class ShotMetadata {
    // Singleplayer world name, or the server's name from the server list
    public String world;
    public boolean multiplayer;
    // For example "minecraft:the_nether"
    public String dimension;
    public Double x;
    public Double y;
    public Double z;
    public Float yaw;
    public Float pitch;
    // In-game day time (0 to 23999), so the gallery can say day or night
    public Long dayTime;
    // Wall clock time in milliseconds since the epoch
    public Long takenAt;
    public boolean favorite;
    // Albums the player put the screenshot in; null when none
    public java.util.List<String> albums;

    public boolean inAlbum(String album) {
        return albums != null && albums.stream().anyMatch(name -> name.equalsIgnoreCase(album));
    }

    public boolean hasPosition() {
        return x != null && y != null && z != null;
    }

    /**
     * A command that puts the player back where the screenshot was taken, in the right dimension
     * and looking the same way. Needs cheats or operator rights, like any /tp.
     */
    public String teleportCommand() {
        if (!hasPosition()) {
            return null;
        }
        String tp = String.format(Locale.ROOT, "tp @s %.2f %.2f %.2f", x, y, z);
        if (yaw != null && pitch != null) {
            tp += String.format(Locale.ROOT, " %.1f %.1f", yaw, pitch);
        }
        if (dimension == null) {
            return "/" + tp;
        }
        return "/execute in " + dimension + " run " + tp;
    }

    /** "Overworld", "The Nether", or the path of a modded dimension's id. */
    public String dimensionName() {
        if (dimension == null) {
            return null;
        }
        return switch (dimension) {
            case "minecraft:overworld" -> "Overworld";
            case "minecraft:the_nether" -> "The Nether";
            case "minecraft:the_end" -> "The End";
            default -> {
                String path = dimension.substring(dimension.indexOf(':') + 1).replace('_', ' ');
                yield path.isEmpty() ? dimension : Character.toUpperCase(path.charAt(0)) + path.substring(1);
            }
        };
    }
}
