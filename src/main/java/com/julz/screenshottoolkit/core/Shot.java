package com.julz.screenshottoolkit.core;

import java.nio.file.Path;

/** One screenshot file in the library. */
public record Shot(Path file, long lastModified, long size, ShotMetadata metadata) {
    public String name() {
        return file.getFileName().toString();
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
