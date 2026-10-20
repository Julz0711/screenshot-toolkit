package com.julz.screenshottoolkit.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * GPU textures for screenshots shown on a screen. Images load in the background and become
 * textures on the render thread; everything is released when the screen closes.
 * <p>
 * Only what was asked for in the last frame starts loading, a few at a time and in the order it
 * was asked for, so scrolling fast through thousands of screenshots never queues up work for
 * thumbnails that are long gone. Textures not drawn for a while are released once there are more
 * than {@code capacity}, which keeps GPU memory flat in big folders.
 */
public class ShotTextures implements AutoCloseable {
    private static final AtomicLong COUNTER = new AtomicLong();

    public record Texture(Identifier id, int width, int height) {
    }

    private static final int MAX_LOADING = 4;
    // Frames a texture stays loaded after it was last drawn, while over capacity
    private static final int KEEP_FRAMES = 2;

    private final Function<Path, CompletableFuture<NativeImage>> loader;
    private final int capacity;
    private final Map<Path, CompletableFuture<NativeImage>> pending = new HashMap<>();
    private final Map<Path, Texture> ready = new HashMap<>();
    private final Map<Path, Long> lastUsed = new HashMap<>();
    private final Set<Path> wanted = new LinkedHashSet<>();
    private final Set<Path> failed = new HashSet<>();
    private long frame;
    private boolean closed;

    public ShotTextures(Function<Path, CompletableFuture<NativeImage>> loader, int capacity) {
        this.loader = loader;
        this.capacity = capacity;
    }

    /** The texture, or null while it loads. Asking for it is what makes it load. */
    public Texture get(Path image) {
        Texture texture = ready.get(image);
        if (!closed) {
            lastUsed.put(image, frame);
            if (texture == null && !failed.contains(image) && !pending.containsKey(image)) {
                wanted.add(image);
            }
        }
        return texture;
    }

    /** Starts loading an image that will be needed soon, like the next one in the viewer. */
    public void prefetch(Path image) {
        get(image);
    }

    public boolean isClosed() {
        return closed;
    }

    public boolean failed(Path image) {
        return failed.contains(image);
    }

    /** Turns finished loads into textures and starts new ones. Call once per frame on the render thread. */
    public void upload() {
        frame++;
        Iterator<Map.Entry<Path, CompletableFuture<NativeImage>>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Path, CompletableFuture<NativeImage>> entry = it.next();
            CompletableFuture<NativeImage> future = entry.getValue();
            if (!future.isDone()) {
                continue;
            }
            it.remove();
            NativeImage image = future.exceptionally(e -> {
                ScreenshotToolkitClient.LOGGER.warn("Could not load {}", entry.getKey(), e);
                return null;
            }).join();
            if (image == null) {
                failed.add(entry.getKey());
                continue;
            }
            ready.put(entry.getKey(), register(image));
        }
        Iterator<Path> next = wanted.iterator();
        while (next.hasNext() && pending.size() < MAX_LOADING && !closed) {
            Path image = next.next();
            if (!ready.containsKey(image) && !pending.containsKey(image)) {
                pending.put(image, loader.apply(image));
            }
            next.remove();
        }
        // Whatever did not fit is asked for again next frame if it is still on screen
        wanted.clear();
        evict();
    }

    private void evict() {
        if (ready.size() <= capacity) {
            return;
        }
        ready.keySet().stream()
                .filter(image -> lastUsed.getOrDefault(image, 0L) < frame - KEEP_FRAMES)
                .sorted((a, b) -> Long.compare(lastUsed.getOrDefault(a, 0L), lastUsed.getOrDefault(b, 0L)))
                .limit(ready.size() - capacity)
                .toList()
                .forEach(this::forget);
    }

    /** For tests: how many textures are loaded right now. */
    public int loadedCount() {
        return ready.size();
    }

    /** Drops one image, for example after it was deleted or cropped. */
    public void forget(Path image) {
        Texture texture = ready.remove(image);
        if (texture != null) {
            Minecraft.getInstance().getTextureManager().release(texture.id());
        }
        failed.remove(image);
        lastUsed.remove(image);
        CompletableFuture<NativeImage> future = pending.remove(image);
        if (future != null) {
            future.thenAccept(NativeImage::close);
        }
    }

    @Override
    public void close() {
        closed = true;
        for (Texture texture : ready.values()) {
            Minecraft.getInstance().getTextureManager().release(texture.id());
        }
        ready.clear();
        for (CompletableFuture<NativeImage> future : pending.values()) {
            future.thenAccept(NativeImage::close);
        }
        pending.clear();
        wanted.clear();
        lastUsed.clear();
    }

    /** Uploads the image as a texture. The texture owns the image from here on. */
    public static Texture register(NativeImage image) {
        Identifier id = Identifier.fromNamespaceAndPath(ScreenshotToolkitClient.MOD_ID, "shot/" + COUNTER.incrementAndGet());
        DynamicTexture texture = new DynamicTexture(() -> "Screenshot Toolkit " + id, image);
        Minecraft.getInstance().getTextureManager().register(id, texture);
        return new Texture(id, image.getWidth(), image.getHeight());
    }
}
