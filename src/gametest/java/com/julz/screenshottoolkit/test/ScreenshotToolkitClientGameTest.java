package com.julz.screenshottoolkit.test;

import com.julz.screenshottoolkit.client.Crop;
import com.julz.screenshottoolkit.client.CropChoiceScreen;
import com.julz.screenshottoolkit.client.GalleryScreen;
import com.julz.screenshottoolkit.client.RenameScreen;
import com.julz.screenshottoolkit.client.ScreenshotToolkitClient;
import com.julz.screenshottoolkit.client.ScreenshotToolkitConfigScreen;
import com.julz.screenshottoolkit.client.ShotGrid;
import com.julz.screenshottoolkit.client.ShotScreen;
import com.julz.screenshottoolkit.client.Thumbnails;
import com.julz.screenshottoolkit.config.ModConfig;
import com.julz.screenshottoolkit.core.ScreenshotLibrary;
import com.julz.screenshottoolkit.core.Shot;
import com.julz.screenshottoolkit.core.ShotMetadata;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
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
        testQuickDelete(context, library);

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
        testCropWithMouse(context, library, red);
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

    /** Deletes from the grid with the Delete key and with the corner button, undoing each. */
    private void testQuickDelete(ClientGameTestContext context, ScreenshotLibrary library) {
        Path green = library.directory().resolve("2026-01-02_10.00.00.png");
        Path blue = library.directory().resolve("2026-01-03_10.00.00.png");
        int scale = context.computeOnClient(client -> client.getWindow().getGuiScale());

        // Delete key on the selected thumbnail
        context.runOnClient(client -> {
            GalleryScreen gallery = (GalleryScreen) client.gui.screen();
            gallery.visibleShots().stream().filter(shot -> shot.file().equals(green)).findFirst().ifPresent(gallery.grid()::select);
            gallery.setFocused(gallery.grid());
        });
        context.getInput().pressKey(InputConstants.KEY_DELETE);
        context.waitTick();
        assertTrue(!Files.exists(green), "Delete key moves the selected screenshot to the trash");
        boolean listed = context.computeOnClient(client -> ((GalleryScreen) client.gui.screen()).visibleShots().stream()
                .anyMatch(shot -> shot.file().equals(green)));
        assertTrue(!listed, "deleted screenshot leaves the grid");
        clickGalleryUndo(context, scale);
        context.waitFor(client -> Files.exists(green) && ((GalleryScreen) client.gui.screen()).isLoaded());

        // The corner button on a hovered thumbnail
        int[] corner = context.computeOnClient(client -> {
            GalleryScreen gallery = (GalleryScreen) client.gui.screen();
            int index = gallery.visibleShots().stream().map(Shot::file).toList().indexOf(blue);
            int[] cell = gallery.grid().cellPosition(index);
            return new int[]{cell[0] + 7, cell[1] + 7};
        });
        context.getInput().setCursorPos(corner[0] * scale, corner[1] * scale);
        context.waitTicks(2);
        context.takeScreenshot("screenshot-toolkit-gallery-delete-hover");
        String before = context.computeOnClient(client -> {
            GalleryScreen gallery = (GalleryScreen) client.gui.screen();
            ShotGrid grid = gallery.grid();
            return "corner " + Arrays.toString(corner) + ", scale " + scale + ", grid sees " + Arrays.toString(grid.lastMouse())
                    + ", grid " + grid.getX() + "," + grid.getY() + " " + grid.getWidth() + "x" + grid.getHeight()
                    + ", shots " + gallery.visibleShots().stream().map(shot -> shot.file().getFileName().toString()).toList()
                    + ", loaded " + gallery.isLoaded();
        });
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTick();
        String after = context.computeOnClient(client -> client.gui.screen().getClass().getSimpleName()
                + ", green " + Files.exists(green));
        assertTrue(!Files.exists(blue), "the corner button moves the screenshot to the trash; " + before + "; after: " + after);
        boolean stillGallery = context.computeOnClient(client -> client.gui.screen() instanceof GalleryScreen);
        assertTrue(stillGallery, "deleting does not open the viewer");
        clickGalleryUndo(context, scale);
        context.waitFor(client -> Files.exists(blue) && ((GalleryScreen) client.gui.screen()).isLoaded());
        context.waitFor(client -> Files.exists(Thumbnails.file(library, blue)));
    }

    private static void clickGalleryUndo(ClientGameTestContext context, int scale) {
        int[] center = context.computeOnClient(client -> {
            Button b = ((GalleryScreen) client.gui.screen()).undoButton();
            assertTrue(b.visible, "Undo is offered after a delete");
            return new int[]{b.getX() + b.getWidth() / 2, b.getY() + b.getHeight() / 2};
        });
        context.getInput().setCursorPos(center[0] * scale, center[1] * scale);
        context.waitTicks(2);
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTicks(2);
    }

    /** Crops, renames and crops again the way a player does, with the mouse and the keyboard. */
    private void testCropWithMouse(ClientGameTestContext context, ScreenshotLibrary library, Path source) {
        context.waitFor(client -> ((ShotScreen) client.gui.screen()).imageBounds() != null);
        double scale = cursorScale(context);

        // Crop to a new image
        clickButton(context, scale, screen -> screen.cropButton());
        boolean cropping = context.computeOnClient(client -> ((ShotScreen) client.gui.screen()).isCropping());
        assertTrue(cropping, "clicking Crop starts cropping");
        dragOverImage(context, scale);
        context.takeScreenshot("screenshot-toolkit-crop-selection");
        clickButton(context, scale, screen -> screen.cropButton());
        context.waitForScreen(CropChoiceScreen.class);
        context.takeScreenshot("screenshot-toolkit-crop-choice");
        clickChoice(context, scale, CropChoiceScreen::newButton);
        context.waitForScreen(ShotScreen.class);
        Path cropped = library.directory().resolve("2026-01-01_10.00.00_crop.png");
        context.waitFor(client -> Files.exists(cropped), 20);
        // Half of 320x180, give or take the rounding of screen pixels to image pixels
        assertSize(cropped, 160, 90);
        assertTrue(Files.exists(source), "the original is kept");
        Path shown = context.computeOnClient(client -> ((ShotScreen) client.gui.screen()).currentFile());
        assertEquals(cropped, shown, "viewer shows the cropped copy");

        // Rename the cropped copy through the Rename screen
        clickButton(context, scale, screen -> screen.renameButton());
        context.waitForScreen(RenameScreen.class);
        context.runOnClient(client -> ((RenameScreen) client.gui.screen()).nameBox().setValue("Renamed shot"));
        context.takeScreenshot("screenshot-toolkit-rename");
        context.getInput().pressKey(InputConstants.KEY_RETURN);
        context.waitForScreen(ShotScreen.class);
        Path renamed = library.directory().resolve("Renamed shot.png");
        assertTrue(Files.exists(renamed), "renamed file exists");
        assertTrue(!Files.exists(cropped), "old name is gone");
        Path shownAfterRename = context.computeOnClient(client -> ((ShotScreen) client.gui.screen()).currentFile());
        assertEquals(renamed, shownAfterRename, "viewer shows the renamed file");

        // Crop it again, this time replacing it
        context.waitFor(client -> ((ShotScreen) client.gui.screen()).imageBounds() != null);
        clickButton(context, scale, screen -> screen.cropButton());
        dragOverImage(context, scale);
        clickButton(context, scale, screen -> screen.cropButton());
        context.waitForScreen(CropChoiceScreen.class);
        clickChoice(context, scale, CropChoiceScreen::replaceButton);
        context.waitForScreen(ShotScreen.class);
        assertSize(renamed, 80, 45);
        assertTrue(Files.exists(library.trashDirectory().resolve("Renamed shot.png")), "the replaced original is in the trash");
        context.waitTicks(5);
        context.takeScreenshot("screenshot-toolkit-viewer-replaced");

        // Back in the gallery, the new name is listed and shown under its thumbnail
        clickButton(context, scale, screen -> screen.backButton());
        context.waitForScreen(GalleryScreen.class);
        context.waitFor(client -> ((GalleryScreen) client.gui.screen()).isLoaded()
                && ((GalleryScreen) client.gui.screen()).visibleShots().stream().anyMatch(shot -> shot.file().equals(renamed)));
        context.waitFor(client -> Files.exists(Thumbnails.file(library, renamed)));
        context.waitTicks(10);
        context.takeScreenshot("screenshot-toolkit-gallery-renamed");
        try {
            library.moveToTrash(renamed);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /** Drags from a quarter to three quarters of the image, then checks an area is selected. */
    private static void dragOverImage(ClientGameTestContext context, double scale) {
        int[] area = context.computeOnClient(client -> ((ShotScreen) client.gui.screen()).imageBounds());
        TestInput input = context.getInput();
        input.setCursorPos((area[0] + area[2] * 0.25) * scale, (area[1] + area[3] * 0.25) * scale);
        context.waitTick();
        input.holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTick();
        for (int step = 1; step <= 5; step++) {
            input.moveCursor(area[2] * 0.1 * scale, area[3] * 0.1 * scale);
            context.waitTick();
        }
        input.releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTick();
        int[] selection = context.computeOnClient(client -> ((ShotScreen) client.gui.screen()).cropSelection());
        assertTrue(selection != null, "dragging over the image selects an area, image at " + Arrays.toString(area));
    }

    private static void clickChoice(ClientGameTestContext context, double scale, Function<CropChoiceScreen, Button> button) {
        int[] center = context.computeOnClient(client -> {
            Button b = button.apply((CropChoiceScreen) client.gui.screen());
            return new int[]{b.getX() + b.getWidth() / 2, b.getY() + b.getHeight() / 2};
        });
        context.getInput().setCursorPos(center[0] * scale, center[1] * scale);
        context.waitTicks(2);
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTicks(2);
    }

    private static void assertSize(Path file, int width, int height) {
        try (NativeImage image = Thumbnails.read(file)) {
            assertTrue(Math.abs(image.getWidth() - width) <= 8, file.getFileName() + " width " + image.getWidth() + ", expected about " + width);
            assertTrue(Math.abs(image.getHeight() - height) <= 8, file.getFileName() + " height " + image.getHeight() + ", expected about " + height);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /** Cursor units per GUI pixel, measured by moving the cursor and asking the screen where it is. */
    private static double cursorScale(ClientGameTestContext context) {
        context.getInput().setCursorPos(800, 400);
        context.waitTicks(2);
        int[] seen = context.computeOnClient(client -> ((ShotScreen) client.gui.screen()).lastMouse());
        int guiScale = context.computeOnClient(client -> client.getWindow().getGuiScale());
        assertTrue(seen[0] > 0, "the screen sees the cursor; at " + Arrays.toString(seen) + ", GUI scale " + guiScale);
        return 800.0 / seen[0];
    }

    private static void clickButton(ClientGameTestContext context, double scale, Function<ShotScreen, Button> button) {
        int[] center = context.computeOnClient(client -> {
            Button b = button.apply((ShotScreen) client.gui.screen());
            return new int[]{b.getX() + b.getWidth() / 2, b.getY() + b.getHeight() / 2};
        });
        context.getInput().setCursorPos(center[0] * scale, center[1] * scale);
        context.waitTicks(2);
        int[] seen = context.computeOnClient(client -> ((ShotScreen) client.gui.screen()).lastMouse());
        assertTrue(Math.abs(seen[0] - center[0]) <= 2 && Math.abs(seen[1] - center[1]) <= 2,
                "cursor on the button at " + Arrays.toString(center) + ", screen sees " + Arrays.toString(seen) + ", scale " + scale);
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTicks(2);
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
