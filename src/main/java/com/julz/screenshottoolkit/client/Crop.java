package com.julz.screenshottoolkit.client;

import com.julz.screenshottoolkit.core.ScreenshotLibrary;
import com.julz.screenshottoolkit.core.ShotMetadata;
import com.mojang.blaze3d.platform.NativeImage;

import java.io.IOException;
import java.nio.file.Path;

/** Saves part of a screenshot as a new file next to it. The original is never changed. */
public final class Crop {
    private Crop() {
    }

    /** Writes name_crop.png (or name_crop_1.png and so on) and copies the metadata. Returns the new file. */
    public static Path save(ScreenshotLibrary library, Path image, int x, int y, int width, int height) throws IOException {
        String name = image.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        Path target = ScreenshotLibrary.uniqueName(library.directory(), base + "_crop.png");
        try (NativeImage source = Thumbnails.read(image)) {
            int cropX = Math.clamp(x, 0, source.getWidth() - 1);
            int cropY = Math.clamp(y, 0, source.getHeight() - 1);
            int cropWidth = Math.clamp(width, 1, source.getWidth() - cropX);
            int cropHeight = Math.clamp(height, 1, source.getHeight() - cropY);
            try (NativeImage cropped = new NativeImage(cropWidth, cropHeight, false)) {
                source.copyRect(cropped, cropX, cropY, 0, 0, cropWidth, cropHeight, false, false);
                cropped.writeToFile(target);
            }
        }
        ShotMetadata metadata = library.readMetadata(image);
        if (metadata != null) {
            metadata.favorite = false;
            library.writeMetadata(target, metadata);
        }
        return target;
    }
}
