package com.julz.screenshottoolkit.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * GPU textures for screenshots shown on a screen. Images load in the background and become
 * textures on the render thread; everything is released when the screen closes.
 */
public class ShotTextures implements AutoCloseable {
    private static final AtomicLong COUNTER = new AtomicLong();

    public record Texture(Identifier id, int width, int height) {
    }

    private final Function<Path, CompletableFuture<NativeImage>> loader;
    private final Map<Path, CompletableFuture<NativeImage>> pending = new HashMap<>();
    private final Map<Path, Texture> ready = new HashMap<>();
    private final Set<Path> failed = new HashSet<>();
    private boolean closed;

    public ShotTextures(Function<Path, CompletableFuture<NativeImage>> loader) {
        this.loader = loader;
    }

    /** The texture, or null while it loads. Starts loading on the first call. */
    public Texture get(Path image) {
        Texture texture = ready.get(image);
        if (texture == null && !closed && !failed.contains(image) && !pending.containsKey(image)) {
            pending.put(image, loader.apply(image));
        }
        return texture;
    }

    public boolean isClosed() {
        return closed;
    }

    public boolean failed(Path image) {
        return failed.contains(image);
    }

    /** Turns finished loads into textures. Call once per frame on the render thread. */
    public void upload() {
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
    }

    /** Drops one image, for example after it was deleted or cropped. */
    public void forget(Path image) {
        Texture texture = ready.remove(image);
        if (texture != null) {
            Minecraft.getInstance().getTextureManager().release(texture.id());
        }
        failed.remove(image);
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
    }

    /** Uploads the image as a texture. The texture owns the image from here on. */
    public static Texture register(NativeImage image) {
        Identifier id = Identifier.fromNamespaceAndPath(ScreenshotToolkitClient.MOD_ID, "shot/" + COUNTER.incrementAndGet());
        DynamicTexture texture = new DynamicTexture(() -> "Screenshot Toolkit " + id, image);
        Minecraft.getInstance().getTextureManager().register(id, texture);
        return new Texture(id, image.getWidth(), image.getHeight());
    }
}
