package com.julz.screenshottoolkit.core;

import java.nio.file.Path;
import java.util.regex.Pattern;

/** One screenshot file in the library. */
public record Shot(Path file, long lastModified, long size, ShotMetadata metadata) {
    // Names Minecraft and the crop tool give: 2026-10-20_12.36.34.png, ..._2.png, ..._crop.png, ..._crop_1.png
    private static final Pattern DEFAULT_NAME = Pattern.compile("\\d{4}-\\d{2}-\\d{2}_\\d{2}\\.\\d{2}\\.\\d{2}(_\\d+)?(_crop(_\\d+)?)?\\.[A-Za-z]+");

    public String name() {
        return file.getFileName().toString();
    }

    /** The name the player gave it, without extension, or null while it has the name the game gave it. */
    public String customName() {
        String name = name();
        if (DEFAULT_NAME.matcher(name).matches()) {
            return null;
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    public boolean favorite() {
        return metadata != null && metadata.favorite;
    }

    /** When the screenshot was taken: the recorded time if there is one, the file time otherwise. */
    public long takenAt() {
        return metadata != null && metadata.takenAt != null ? metadata.takenAt : lastModified;
    }

    public String world() {
        return metadata != null ? metadata.world : null;
    }
}
