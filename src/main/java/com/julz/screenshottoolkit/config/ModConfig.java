package com.julz.screenshottoolkit.config;

import com.julz.screenshottoolkit.client.ScreenshotToolkitClient;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public class ModConfig {
    private static final Path CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve("screenshot-toolkit.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static ModConfig instance;

    // Copy every screenshot to the system clipboard as an image.
    public boolean copyToClipboard = true;
    // Show a toast with a thumbnail instead of the vanilla chat line.
    public boolean showToast = true;
    // Keep the vanilla "Saved screenshot as ..." chat line as well.
    public boolean keepChatMessage = false;
    // Save world, coordinates, dimension and time next to each screenshot.
    public boolean saveMetadata = true;
    // Days a deleted screenshot stays in screenshots/.trash before it is removed for good.
    public int trashDays = 7;
    // Gallery thumbnail size step, 0 (smallest) to 3; changed with Ctrl+scroll in the gallery.
    public int thumbnailSize = 1;

    public static ModConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (Files.exists(CONFIG_FILE)) {
            try (Reader reader = Files.newBufferedReader(CONFIG_FILE)) {
                instance = GSON.fromJson(reader, ModConfig.class);
            } catch (IOException | JsonParseException e) {
                ScreenshotToolkitClient.LOGGER.error("Failed to read {}, using defaults", CONFIG_FILE, e);
            }
        }
        if (instance == null) {
            instance = new ModConfig();
        }
        instance.sanitize();
        // Writes the file on first launch and adds options introduced by newer versions.
        save();
    }

    public static void save() {
        try (Writer writer = Files.newBufferedWriter(CONFIG_FILE)) {
            GSON.toJson(instance, writer);
        } catch (IOException e) {
            ScreenshotToolkitClient.LOGGER.error("Failed to save {}", CONFIG_FILE, e);
        }
    }

    /** Pulls hand-edited values back into range. */
    public void sanitize() {
        trashDays = Math.clamp(trashDays, 1, 90);
        thumbnailSize = Math.clamp(thumbnailSize, 0, 3);
    }
}
