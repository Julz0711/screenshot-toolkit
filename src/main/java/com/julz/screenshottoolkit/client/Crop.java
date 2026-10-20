package com.julz.screenshottoolkit.client;

import com.julz.screenshottoolkit.core.ScreenshotLibrary;
import com.julz.screenshottoolkit.core.ShotMetadata;
import com.mojang.blaze3d.platform.NativeImage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;

/** Saves part of a screenshot, either as a new file next to it or in place of it. */
public final class Crop {
    private Crop() {
    }

    /** Writes name_crop.png (or name_crop_1.png and so on) and copies the metadata. Returns the new file. */
    public static Path save(ScreenshotLibrary library, Path image, int x, int y, int width, int height) throws IOException {
        String name = image.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        Path target = ScreenshotLibrary.uniqueName(library.directory(), base + "_crop.png");
        write(image, target, x, y, width, height);
        ShotMetadata metadata = library.readMetadata(image);
        if (metadata != null) {
            metadata.favorite = false;
            library.writeMetadata(target, metadata);
        }
        return target;
    }

    /**
     * Crops the screenshot in place. A copy of the original goes to the trash first, so it can be
     * recovered for as long as deleted screenshots are kept. The file keeps its name, date and details.
     */
    public static void replace(ScreenshotLibrary library, Path image, int x, int y, int width, int height) throws IOException {
        FileTime taken = Files.getLastModifiedTime(image);
        Path temp = image.resolveSibling(".crop-" + image.getFileName());
        write(image, temp, x, y, width, height);
        try {
            Path trash = library.trashDirectory();
            Files.createDirectories(trash);
            Path backup = ScreenshotLibrary.uniqueName(trash, image.getFileName().toString());
            Files.copy(image, backup);
            // The trash countdown starts now
            Files.setLastModifiedTime(backup, FileTime.fromMillis(System.currentTimeMillis()));
            Files.move(temp, image, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
        Files.setLastModifiedTime(image, taken);
        Files.deleteIfExists(Thumbnails.file(library, image));
    }

    private static void write(Path image, Path target, int x, int y, int width, int height) throws IOException {
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
    }
}
