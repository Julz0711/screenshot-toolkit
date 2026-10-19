package com.julz.screenshottoolkit.client;

import com.julz.screenshottoolkit.core.ShotMetadata;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.nio.file.Path;
import java.util.function.Consumer;

/** Hooks into the vanilla screenshot key: records where the player is, then handles the saved file. */
public final class ScreenshotCapture {
    private ScreenshotCapture() {
    }

    /**
     * Called on the render thread when a screenshot is taken. The metadata is read now, since the
     * file is written later on another thread, when the player may have moved.
     */
    public static Consumer<Component> wrap(Minecraft client, Consumer<Component> vanilla) {
        ShotMetadata metadata = capture(client);
        return message -> {
            Path file = savedFile(message);
            if (file == null) {
                // Saving failed: vanilla shows the error
                vanilla.accept(message);
                return;
            }
            ScreenshotToolkitClient.onScreenshotSaved(file, metadata, message, vanilla);
        };
    }

    public static ShotMetadata capture(Minecraft client) {
        ShotMetadata metadata = new ShotMetadata();
        metadata.takenAt = System.currentTimeMillis();
        IntegratedServer server = client.getSingleplayerServer();
        ServerData serverData = client.getCurrentServer();
        if (client.hasSingleplayerServer() && server != null) {
            metadata.world = server.getWorldData().getLevelName();
        } else if (serverData != null) {
            metadata.world = serverData.name != null && !serverData.name.isBlank() ? serverData.name : serverData.ip;
            metadata.multiplayer = true;
        }
        if (client.level != null) {
            metadata.dimension = client.level.dimension().identifier().toString();
            metadata.dayTime = client.level.getOverworldClockTime();
        }
        if (client.player != null) {
            metadata.x = client.player.getX();
            metadata.y = client.player.getY();
            metadata.z = client.player.getZ();
            metadata.yaw = client.player.getYRot();
            metadata.pitch = client.player.getXRot();
        }
        return metadata;
    }

    /**
     * The file from vanilla's "Saved screenshot as [name]" message, whose name links to the file.
     * Null when the message is the failure message.
     */
    static Path savedFile(Component message) {
        if (!(message.getContents() instanceof TranslatableContents contents) || !"screenshot.success".equals(contents.getKey())) {
            return null;
        }
        for (Object arg : contents.getArgs()) {
            if (arg instanceof Component component && component.getStyle().getClickEvent() instanceof ClickEvent.OpenFile openFile) {
                return openFile.file().toPath();
            }
        }
        return null;
    }
}
