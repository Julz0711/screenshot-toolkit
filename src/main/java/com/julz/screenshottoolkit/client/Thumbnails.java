package com.julz.screenshottoolkit.client;

import com.julz.screenshottoolkit.core.ScreenshotLibrary;
import com.mojang.blaze3d.platform.NativeImage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Small copies of screenshots in screenshots/.thumbs, made off the main thread, so the gallery
 * opens fast even with thousands of files. A thumbnail is made again when its image is newer.
 */
public final class Thumbnails {
    public static final int WIDTH = 256;

    private static final AtomicInteger THREAD_COUNT = new AtomicInteger();
    static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "Screenshot Toolkit Thumbnails #" + THREAD_COUNT.incrementAndGet());
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    private Thumbnails() {
    }

    public static Path file(ScreenshotLibrary library, Path image) {
        return library.thumbDirectory().resolve(image.getFileName());
    }

    /** Makes the thumbnail if it is missing or out of date. Runs on the calling thread. */
    public static Path ensure(ScreenshotLibrary library, Path image) throws IOException {
        Path thumbnail = file(library, image);
        if (Files.exists(thumbnail)
                && Files.getLastModifiedTime(thumbnail).compareTo(Files.getLastModifiedTime(image)) >= 0) {
            return thumbnail;
        }
        Files.createDirectories(thumbnail.getParent());
        try (NativeImage source = read(image)) {
            int width = Math.min(WIDTH, source.getWidth());
            int height = Math.max(1, Math.round(source.getHeight() * (float) width / source.getWidth()));
            try (NativeImage small = new NativeImage(width, height, false)) {
                source.resizeSubRectTo(0, 0, source.getWidth(), source.getHeight(), small);
                // Written under a temporary name, so a crash never leaves half a thumbnail behind
                Path temp = thumbnail.resolveSibling(thumbnail.getFileName() + ".tmp");
                small.writeToFile(temp);
                Files.move(temp, thumbnail, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return thumbnail;
    }

    /** Loads the thumbnail, making it first if needed. The caller closes the image. */
    public static CompletableFuture<NativeImage> load(ScreenshotLibrary library, Path image) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return read(ensure(library, image));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, EXECUTOR);
    }

    /** Loads a small copy without saving it, for files that have no thumbnail of their own, like the trash. */
    public static CompletableFuture<NativeImage> loadUncached(Path image) {
        return CompletableFuture.supplyAsync(() -> {
            try (NativeImage source = read(image)) {
                int width = Math.min(WIDTH, source.getWidth());
                int height = Math.max(1, Math.round(source.getHeight() * (float) width / source.getWidth()));
                NativeImage small = new NativeImage(width, height, false);
                source.resizeSubRectTo(0, 0, source.getWidth(), source.getHeight(), small);
                return small;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, EXECUTOR);
    }

    /** Loads the full size image. The caller closes it. */
    public static CompletableFuture<NativeImage> loadFull(Path image) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return read(image);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, EXECUTOR);
    }

    public static NativeImage read(Path image) throws IOException {
        try (InputStream in = Files.newInputStream(image)) {
            return NativeImage.read(in);
        }
    }
}
