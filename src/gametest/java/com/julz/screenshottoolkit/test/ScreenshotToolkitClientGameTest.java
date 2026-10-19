package com.julz.screenshottoolkit.test;

import com.julz.screenshottoolkit.client.Crop;
import com.julz.screenshottoolkit.client.GalleryScreen;
import com.julz.screenshottoolkit.client.ScreenshotToolkitClient;
import com.julz.screenshottoolkit.client.ScreenshotToolkitConfigScreen;
import com.julz.screenshottoolkit.client.ShotScreen;
import com.julz.screenshottoolkit.client.Thumbnails;
import com.julz.screenshottoolkit.config.ModConfig;
import com.julz.screenshottoolkit.core.ScreenshotLibrary;
import com.julz.screenshottoolkit.core.Shot;
import com.julz.screenshottoolkit.core.ShotMetadata;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class ScreenshotToolkitClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        // CI has no clipboard tool; the copy is covered by unit tests
        context.runOnClient(client -> ModConfig.getInstance().copyToClipboard = false);
        ScreenshotLibrary library = context.computeOnClient(client -> ScreenshotToolkitClient.library());

        testConfigScreen(context);
        testGallery(context, library);
        testCrop(library);

        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitFor(client -> client.player != null);
            context.waitTicks(20);
            testScreenshotKey(context, library);
            testCleanShotKey(context, library);
        }
    }

    private void testConfigScreen(ClientGameTestContext context) {
        context.setScreen(() -> new ScreenshotToolkitConfigScreen(null));
        context.waitForScreen(ScreenshotToolkitConfigScreen.class);
        context.takeScreenshot("screenshot-toolkit-config-screen");
        context.setScreen(() -> null);
    }

    private void testGallery(ClientGameTestContext context, ScreenshotLibrary library) {
        // Three fake screenshots, one starred with a location
        Path red = writeImage(library, "2026-01-01_10.00.00.png", 0xFF0000FF, 320, 180);
        writeImage(library, "2026-01-02_10.00.00.png", 0xFF00FF00, 320, 180);
        writeImage(library, "2026-01-03_10.00.00.png", 0xFFFF0000, 180, 320);
        ShotMetadata metadata = new ShotMetadata();
        metadata.world = "Test World";
        metadata.dimension = "minecraft:the_nether";
        metadata.x = 10.5;
        metadata.y = 64.0;
        metadata.z = -3.0;
        metadata.favorite = true;
        try {
            library.writeMetadata(red, metadata);
        } catch (IOException e) {
            throw new AssertionError(e);
        }

        context.setScreen(() -> new GalleryScreen(null));
        context.waitForScreen(GalleryScreen.class);
        context.waitFor(client -> ((GalleryScreen) client.gui.screen()).isLoaded());
        int shown = context.computeOnClient(client -> ((GalleryScreen) client.gui.screen()).visibleShots().size());
        assertTrue(shown >= 3, "gallery lists the screenshots, found " + shown);
        // Thumbnails are made in the background
        context.waitFor(client -> Files.exists(Thumbnails.file(library, red)));
        context.waitTicks(10);
        context.takeScreenshot("screenshot-toolkit-gallery");

        // The viewer opens on the starred screenshot
        context.runOnClient(client -> {
            GalleryScreen gallery = (GalleryScreen) client.gui.screen();
            Shot starred = gallery.visibleShots().stream().filter(Shot::favorite).findFirst()
                    .orElseThrow(() -> new AssertionError("starred screenshot missing"));
            client.gui.setScreen(new ShotScreen(gallery, gallery.visibleShots(), gallery.visibleShots().indexOf(starred)));
        });
        context.waitForScreen(ShotScreen.class);
        context.waitTicks(10);
        context.takeScreenshot("screenshot-toolkit-viewer");
        context.setScreen(() -> null);

        // Delete goes to the trash, with the sidecar
        try {
            Path trashed = library.moveToTrash(red);
            assertTrue(Files.exists(trashed), "trashed file exists");
            assertTrue(Files.exists(library.trashDirectory().resolve(trashed.getFileName() + ".json")), "trashed sidecar exists");
            assertTrue(library.list().stream().noneMatch(shot -> shot.file().equals(red)), "trashed file no longer listed");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private void testCrop(ScreenshotLibrary library) {
        Path source = writeImage(library, "2026-01-04_10.00.00.png", 0xFF808080, 200, 100);
        try {
            Path cropped = Crop.save(library, source, 10, 20, 50, 40);
            try (NativeImage image = Thumbnails.read(cropped)) {
                assertEquals(50, image.getWidth(), "cropped width");
                assertEquals(40, image.getHeight(), "cropped height");
            }
            assertEquals("2026-01-04_10.00.00_crop.png", cropped.getFileName().toString(), "cropped file name");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private void testScreenshotKey(ClientGameTestContext context, ScreenshotLibrary library) {
        Set<Path> before = files(library);
        context.getInput().pressKey(options -> options.keyScreenshot);
        Path shot = waitForNewScreenshot(context, library, before);
        context.waitFor(client -> library.readMetadata(shot) != null);
        ShotMetadata metadata = library.readMetadata(shot);
        assertTrue(metadata.world != null && !metadata.world.isBlank(), "world name recorded");
        assertEquals("minecraft:overworld", metadata.dimension, "dimension recorded");
        assertTrue(metadata.hasPosition(), "position recorded");
        assertTrue(metadata.teleportCommand().startsWith("/execute in minecraft:overworld run tp @s "), "teleport command");
        context.waitFor(client -> Files.exists(Thumbnails.file(library, shot)));
        // The toast replaces the chat line
        context.waitTicks(5);
        context.takeScreenshot("screenshot-toolkit-toast");
    }

    private void testCleanShotKey(ClientGameTestContext context, ScreenshotLibrary library) {
        Set<Path> before = files(library);
        context.getInput().pressKey(ScreenshotToolkitClientGameTest::cleanShotKey);
        waitForNewScreenshot(context, library, before);
        context.waitTicks(2);
        boolean hidden = context.computeOnClient(client -> client.gui.hud.isHidden());
        assertTrue(!hidden, "HUD is shown again after a clean shot");
    }

    private static Path waitForNewScreenshot(ClientGameTestContext context, ScreenshotLibrary library, Set<Path> before) {
        context.waitFor(client -> files(library).stream().anyMatch(file -> !before.contains(file)));
        return files(library).stream().filter(file -> !before.contains(file)).findFirst().orElseThrow();
    }

    private static Set<Path> files(ScreenshotLibrary library) {
        try {
            return library.list().stream().map(Shot::file).collect(Collectors.toSet());
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static Path writeImage(ScreenshotLibrary library, String name, int abgr, int width, int height) {
        try {
            Files.createDirectories(library.directory());
            Path file = library.directory().resolve(name);
            try (NativeImage image = new NativeImage(width, height, false)) {
                image.fillRect(0, 0, width, height, abgr);
                image.writeToFile(file);
            }
            return file;
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static KeyMapping cleanShotKey(Options options) {
        return Arrays.stream(options.keyMappings)
                .filter(mapping -> mapping.getName().equals("key.screenshot-toolkit.cleanShot"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Clean shot key mapping not registered"));
    }

    private static void assertTrue(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }

    private static void assertEquals(Object expected, Object actual, String what) {
        if (!expected.equals(actual)) {
            throw new AssertionError(what + ": expected " + expected + " but was " + actual);
        }
    }
}
