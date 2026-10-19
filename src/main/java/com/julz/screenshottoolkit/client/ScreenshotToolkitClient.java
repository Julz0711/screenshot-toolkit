package com.julz.screenshottoolkit.client;

import com.julz.screenshottoolkit.config.ModConfig;
import com.julz.screenshottoolkit.core.ImageClipboard;
import com.julz.screenshottoolkit.core.ScreenshotLibrary;
import com.julz.screenshottoolkit.core.ShotMetadata;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class ScreenshotToolkitClient implements ClientModInitializer {
    public static final String MOD_ID = "screenshot-toolkit";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath(MOD_ID, "general"));
    // One copy at a time, so a burst of screenshots ends with the last one on the clipboard
    private static final ExecutorService CLIPBOARD = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Screenshot Toolkit Clipboard");
        thread.setDaemon(true);
        return thread;
    });

    private static KeyMapping galleryKey;
    private static KeyMapping cleanShotKey;

    @Override
    public void onInitializeClient() {
        galleryKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.screenshot-toolkit.gallery",
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_F9,
                CATEGORY
        ));
        cleanShotKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.screenshot-toolkit.cleanShot",
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_F6,
                CATEGORY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (galleryKey.consumeClick()) {
                if (client.gui.screen() == null) {
                    client.gui.setScreen(new GalleryScreen(null));
                }
            }
            while (cleanShotKey.consumeClick()) {
                CleanShot.request(client);
            }
        });

        ClientLifecycleEvents.CLIENT_STARTED.register(client -> Thumbnails.EXECUTOR.execute(() -> {
            try {
                int removed = library().emptyTrash(Duration.ofDays(config().trashDays), System.currentTimeMillis());
                if (removed > 0) {
                    LOGGER.info("Removed {} screenshots from the trash", removed);
                }
            } catch (IOException e) {
                LOGGER.warn("Could not empty the screenshot trash", e);
            }
        }));
    }

    public static ModConfig config() {
        return ModConfig.getInstance();
    }

    public static ScreenshotLibrary library() {
        return new ScreenshotLibrary(Minecraft.getInstance().gameDirectory.toPath().resolve("screenshots"));
    }

    /**
     * Called on the IO thread once vanilla wrote a screenshot. Saves the metadata, makes the
     * thumbnail, then shows the toast and copies the image to the clipboard.
     */
    static void onScreenshotSaved(Path file, ShotMetadata metadata, Component message, Consumer<Component> vanilla) {
        ModConfig config = config();
        ScreenshotLibrary library = library();
        if (config.saveMetadata) {
            try {
                library.writeMetadata(file, metadata);
            } catch (IOException e) {
                LOGGER.warn("Could not save metadata for {}", file, e);
            }
        }
        NativeImage thumbnail = null;
        try {
            // Also warms the gallery's thumbnail cache
            Path thumbnailFile = Thumbnails.ensure(library, file);
            if (config.showToast) {
                thumbnail = Thumbnails.read(thumbnailFile);
            }
        } catch (IOException e) {
            LOGGER.warn("Could not make a thumbnail for {}", file, e);
        }

        NativeImage toastImage = thumbnail;
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            ScreenshotToast toast = null;
            if (config.showToast) {
                ShotTextures.Texture texture = toastImage != null ? ShotTextures.register(toastImage) : null;
                toast = new ScreenshotToast(texture, Component.translatable("screenshot-toolkit.toast.saved"),
                        config.copyToClipboard ? Component.translatable("screenshot-toolkit.toast.copying") : galleryHint(file));
                client.gui.toastManager().addToast(toast);
            }
            if (!config.showToast || config.keepChatMessage) {
                vanilla.accept(message);
            }
            if (config.copyToClipboard) {
                copyToClipboard(client, file, toast);
            }
        });
    }

    /**
     * Copies the image in the background. The result updates the given toast, or shows a small
     * system toast when there is none.
     */
    static void copyToClipboard(Minecraft client, Path file, ScreenshotToast toast) {
        ImageClipboard.copy(file, CLIPBOARD).thenAccept(result -> client.execute(() -> {
            Component title;
            Component detail;
            if (result.success()) {
                title = Component.translatable(toast != null ? "screenshot-toolkit.toast.copied" : "screenshot-toolkit.toast.copiedFromGallery");
                detail = galleryHint(file);
            } else {
                LOGGER.warn("Could not copy {} to the clipboard: {}", file,
                        result.missingTool() != null ? result.missingTool() + " is not installed" : result.error());
                title = Component.translatable("screenshot-toolkit.toast.notCopied");
                detail = result.missingTool() != null
                        ? Component.translatable("screenshot-toolkit.toast.install", result.missingTool())
                        : Component.literal(result.error() != null ? result.error() : "");
            }
            if (toast != null) {
                toast.update(title, detail);
            } else {
                SystemToast.addOrUpdate(client.gui.toastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION, title, detail);
            }
        }));
    }

    /** "Press F9 to open the gallery", or the file name when the gallery key is unbound. */
    private static Component galleryHint(Path file) {
        if (galleryKey == null || galleryKey.isUnbound()) {
            return Component.literal(file.getFileName().toString());
        }
        return Component.translatable("screenshot-toolkit.toast.galleryHint", galleryKey.getTranslatedKeyMessage());
    }

    public static KeyMapping galleryKey() {
        return galleryKey;
    }
}
