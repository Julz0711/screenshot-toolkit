package com.julz.screenshottoolkit.test;

import com.julz.screenshottoolkit.client.AlbumScreen;
import com.julz.screenshottoolkit.client.Crop;
import com.julz.screenshottoolkit.client.CropChoiceScreen;
import com.julz.screenshottoolkit.client.GalleryScreen;
import com.julz.screenshottoolkit.client.NameScreen;
import com.julz.screenshottoolkit.client.RenameScreen;
import com.julz.screenshottoolkit.client.ScreenshotToolkitClient;
import com.julz.screenshottoolkit.client.ScreenshotToolkitConfigScreen;
import com.julz.screenshottoolkit.client.ShotGrid;
import com.julz.screenshottoolkit.client.ShotScreen;
import com.julz.screenshottoolkit.client.Thumbnails;
import com.julz.screenshottoolkit.client.TrashScreen;
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
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

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
        testMultiDelete(context, library);
        testDragSelect(context, library);
        testThumbnailSize(context);
        testTrash(context, library);
        testAlbums(context, library);

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
        testZoom(context);
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
            // Selecting green scrolled the grid; bring blue's row back into view
            gallery.grid().select(gallery.visibleShots().get(index));
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
        boolean back = context.computeOnClient(client -> ((GalleryScreen) client.gui.screen()).visibleShots().stream()
                .anyMatch(shot -> shot.file().equals(blue)));
        assertTrue(back, "Undo puts the screenshot back in the grid");
    }

    /** Checks thumbnails with the corner box, a plain click and Space, then deletes them together and undoes it. */
    private void testMultiDelete(ClientGameTestContext context, ScreenshotLibrary library) {
        Path green = library.directory().resolve("2026-01-02_10.00.00.png");
        Path blue = library.directory().resolve("2026-01-03_10.00.00.png");
        int scale = context.computeOnClient(client -> client.getWindow().getGuiScale());

        // Space checks the thumbnail under the keyboard cursor; Escape lets go before it closes anything
        context.runOnClient(client -> {
            GalleryScreen gallery = (GalleryScreen) client.gui.screen();
            gallery.grid().select(shotFor(gallery, green));
            gallery.setFocused(gallery.grid());
        });
        context.getInput().pressKey(InputConstants.KEY_SPACE);
        context.waitTick();
        assertTrue(context.computeOnClient(client -> ((GalleryScreen) client.gui.screen()).grid().isChecking()), "Space checks a thumbnail");
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        context.waitTick();
        boolean cleared = context.computeOnClient(client -> client.gui.screen() instanceof GalleryScreen gallery && !gallery.grid().isChecking());
        assertTrue(cleared, "Escape clears the checked thumbnails and keeps the gallery open");

        // The check box on blue, then a plain click on green, which checks instead of opening
        int[] box = context.computeOnClient(client -> {
            GalleryScreen gallery = (GalleryScreen) client.gui.screen();
            gallery.grid().select(shotFor(gallery, blue));
            int[] cell = gallery.grid().cellPosition(gallery.visibleShots().indexOf(shotFor(gallery, blue)));
            return new int[]{cell[0] + gallery.grid().thumbWidth() - 8, cell[1] + 7};
        });
        clickAt(context, scale, box[0], box[1]);
        int[] middle = context.computeOnClient(client -> {
            GalleryScreen gallery = (GalleryScreen) client.gui.screen();
            gallery.grid().select(shotFor(gallery, green));
            int[] cell = gallery.grid().cellPosition(gallery.visibleShots().indexOf(shotFor(gallery, green)));
            return new int[]{cell[0] + gallery.grid().thumbWidth() / 2, cell[1] + gallery.grid().thumbHeight() / 2};
        });
        clickAt(context, scale, middle[0], middle[1]);
        List<Path> checked = context.computeOnClient(client -> client.gui.screen() instanceof GalleryScreen gallery
                ? gallery.grid().checkedShots().stream().map(Shot::file).toList() : List.of());
        assertTrue(checked.size() == 2 && checked.contains(green) && checked.contains(blue),
                "the box and a click while checking check both, got " + checked);
        context.takeScreenshot("screenshot-toolkit-gallery-checked");

        int[] delete = context.computeOnClient(client -> {
            Button b = ((GalleryScreen) client.gui.screen()).bulkDeleteButton();
            assertTrue(b.visible, "the footer offers bulk delete");
            return new int[]{b.getX() + b.getWidth() / 2, b.getY() + b.getHeight() / 2};
        });
        clickAt(context, scale, delete[0], delete[1]);
        assertTrue(!Files.exists(green) && !Files.exists(blue), "bulk delete moves both to the trash");
        assertTrue(!context.computeOnClient(client -> ((GalleryScreen) client.gui.screen()).grid().isChecking()), "nothing is checked after deleting");

        clickGalleryUndo(context, scale);
        context.waitFor(client -> Files.exists(green) && Files.exists(blue) && ((GalleryScreen) client.gui.screen()).isLoaded());
        boolean back = context.computeOnClient(client -> {
            List<Path> files = ((GalleryScreen) client.gui.screen()).visibleShots().stream().map(Shot::file).toList();
            return files.contains(green) && files.contains(blue);
        });
        assertTrue(back, "one Undo brings the whole batch back");
    }

    /** A box dragged from one thumbnail to another checks everything it touches and opens nothing. */
    private void testDragSelect(ClientGameTestContext context, ScreenshotLibrary library) {
        Path green = library.directory().resolve("2026-01-02_10.00.00.png");
        Path blue = library.directory().resolve("2026-01-03_10.00.00.png");
        int scale = context.computeOnClient(client -> client.getWindow().getGuiScale());
        int[] points = context.computeOnClient(client -> {
            GalleryScreen gallery = (GalleryScreen) client.gui.screen();
            ShotGrid grid = gallery.grid();
            grid.select(gallery.visibleShots().getFirst());
            int[] from = grid.cellPosition(gallery.visibleShots().indexOf(shotFor(gallery, blue)));
            int[] to = grid.cellPosition(gallery.visibleShots().indexOf(shotFor(gallery, green)));
            // Green's row may only be partly visible, so aim at the top of its thumbnail
            return new int[]{from[0] + grid.thumbWidth() / 2, from[1] + grid.thumbHeight() / 2, to[0] + grid.thumbWidth() / 2, to[1] + 10};
        });
        TestInput input = context.getInput();
        input.setCursorPos(points[0] * scale, points[1] * scale);
        context.waitTick();
        input.holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTick();
        for (int step = 1; step <= 5; step++) {
            input.moveCursor((points[2] - points[0]) * scale / 5.0, (points[3] - points[1]) * scale / 5.0);
            context.waitTick();
        }
        context.takeScreenshot("screenshot-toolkit-gallery-drag-select");
        input.releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTick();
        String state = context.computeOnClient(client -> client.gui.screen() instanceof GalleryScreen gallery
                ? gallery.grid().checkedShots().stream().map(shot -> shot.file().getFileName().toString()).toList().toString()
                : "left the gallery for " + client.gui.screen());
        assertTrue(state.contains(green.getFileName().toString()) && state.contains(blue.getFileName().toString()),
                "dragging a box checks the thumbnails it touches, got " + state);
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        context.waitTick();
    }

    private void testThumbnailSize(ClientGameTestContext context) {
        int[] sizes = context.computeOnClient(client -> {
            ShotGrid grid = ((GalleryScreen) client.gui.screen()).grid();
            int before = grid.thumbWidth();
            grid.changeSize(1);
            int bigger = grid.thumbWidth();
            grid.changeSize(-1);
            return new int[]{before, bigger, grid.thumbWidth()};
        });
        assertTrue(sizes[1] > sizes[0] && sizes[2] == sizes[0], "thumbnail size steps up and back, got " + Arrays.toString(sizes));
    }

    /** Deletes a screenshot, finds it in the trash and restores it from there. */
    private void testTrash(ClientGameTestContext context, ScreenshotLibrary library) {
        Path green = library.directory().resolve("2026-01-02_10.00.00.png");
        int scale = context.computeOnClient(client -> client.getWindow().getGuiScale());
        context.runOnClient(client -> {
            GalleryScreen gallery = (GalleryScreen) client.gui.screen();
            gallery.grid().select(shotFor(gallery, green));
            gallery.setFocused(gallery.grid());
        });
        context.getInput().pressKey(InputConstants.KEY_DELETE);
        context.waitTick();
        assertTrue(!Files.exists(green), "Delete key trashes the screenshot");

        int[] trash = context.computeOnClient(client -> {
            Button b = ((GalleryScreen) client.gui.screen()).trashButton();
            return new int[]{b.getX() + b.getWidth() / 2, b.getY() + b.getHeight() / 2};
        });
        clickAt(context, scale, trash[0], trash[1]);
        context.waitForScreen(TrashScreen.class);
        context.waitFor(client -> ((TrashScreen) client.gui.screen()).isLoaded());
        int[] cell = context.computeOnClient(client -> {
            ShotGrid grid = ((TrashScreen) client.gui.screen()).grid();
            int index = grid.shots().stream().map(shot -> shot.name()).toList().indexOf(green.getFileName().toString());
            assertTrue(index >= 0, "the trash lists the deleted screenshot");
            grid.select(grid.shots().get(index));
            int[] position = grid.cellPosition(index);
            return new int[]{position[0] + grid.thumbWidth() / 2, position[1] + 10};
        });
        clickAt(context, scale, cell[0], cell[1]);
        context.waitTicks(10);
        context.takeScreenshot("screenshot-toolkit-trash");
        int[] restore = context.computeOnClient(client -> {
            Button b = ((TrashScreen) client.gui.screen()).restoreButton();
            assertTrue(b.active, "a click checks the screenshot so it can be restored");
            return new int[]{b.getX() + b.getWidth() / 2, b.getY() + b.getHeight() / 2};
        });
        clickAt(context, scale, restore[0], restore[1]);
        assertTrue(Files.exists(green), "Restore puts the screenshot back");

        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        context.waitForScreen(GalleryScreen.class);
        context.waitFor(client -> ((GalleryScreen) client.gui.screen()).isLoaded()
                && ((GalleryScreen) client.gui.screen()).visibleShots().stream().anyMatch(shot -> shot.file().equals(green)));
    }

    /** Puts two screenshots in a new album, shows only that album, then all screenshots again. */
    private void testAlbums(ClientGameTestContext context, ScreenshotLibrary library) {
        Path green = library.directory().resolve("2026-01-02_10.00.00.png");
        Path blue = library.directory().resolve("2026-01-03_10.00.00.png");
        int scale = context.computeOnClient(client -> client.getWindow().getGuiScale());
        for (Path file : List.of(green, blue)) {
            context.runOnClient(client -> {
                GalleryScreen gallery = (GalleryScreen) client.gui.screen();
                gallery.grid().select(shotFor(gallery, file));
                gallery.setFocused(gallery.grid());
            });
            context.getInput().pressKey(InputConstants.KEY_SPACE);
            context.waitTick();
        }
        clickCenter(context, scale, client -> ((GalleryScreen) client.gui.screen()).bulkAlbumButton());
        context.waitForScreen(AlbumScreen.class);
        clickCenter(context, scale, client -> ((AlbumScreen) client.gui.screen()).newButton());
        context.waitForScreen(NameScreen.class);
        context.runOnClient(client -> ((NameScreen) client.gui.screen()).nameBox().setValue("Builds"));
        context.getInput().pressKey(InputConstants.KEY_RETURN);
        context.waitForScreen(AlbumScreen.class);
        assertTrue(library.readMetadata(green).inAlbum("Builds") && library.readMetadata(blue).inAlbum("Builds"),
                "a new album made while adding gets the selected screenshots");
        context.waitTicks(2);
        context.takeScreenshot("screenshot-toolkit-albums");
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        context.waitForScreen(GalleryScreen.class);
        context.waitFor(client -> ((GalleryScreen) client.gui.screen()).isLoaded());

        // Show only the album
        clickCenter(context, scale, client -> ((GalleryScreen) client.gui.screen()).albumsButton());
        context.waitForScreen(AlbumScreen.class);
        clickCenter(context, scale, client -> ((AlbumScreen) client.gui.screen()).albumButtons().get(1));
        context.waitForScreen(GalleryScreen.class);
        context.waitFor(client -> ((GalleryScreen) client.gui.screen()).isLoaded());
        List<Path> shown = context.computeOnClient(client -> ((GalleryScreen) client.gui.screen()).visibleShots().stream().map(Shot::file).toList());
        assertTrue(shown.size() == 2 && shown.contains(green) && shown.contains(blue), "the album shows its two screenshots, got " + shown);
        context.waitTicks(5);
        context.takeScreenshot("screenshot-toolkit-gallery-album");

        // And everything again
        clickCenter(context, scale, client -> ((GalleryScreen) client.gui.screen()).albumsButton());
        context.waitForScreen(AlbumScreen.class);
        clickCenter(context, scale, client -> ((AlbumScreen) client.gui.screen()).albumButtons().getFirst());
        context.waitForScreen(GalleryScreen.class);
        boolean all = context.computeOnClient(client -> ((GalleryScreen) client.gui.screen()).filter().album() == null);
        assertTrue(all, "All Screenshots clears the album filter");
    }

    private static void clickCenter(ClientGameTestContext context, int scale, Function<Minecraft, Button> button) {
        int[] center = context.computeOnClient(client -> {
            Button b = button.apply(client);
            assertTrue(b.visible && b.active, "button " + b.getMessage().getString() + " can be pressed");
            return new int[]{b.getX() + b.getWidth() / 2, b.getY() + b.getHeight() / 2};
        });
        clickAt(context, scale, center[0], center[1]);
    }

    /** Scrolling zooms in on the image and a double click fits it again. */
    private void testZoom(ClientGameTestContext context) {
        context.waitFor(client -> ((ShotScreen) client.gui.screen()).imageBounds() != null);
        float zoomed = context.computeOnClient(client -> {
            ShotScreen screen = (ShotScreen) client.gui.screen();
            int[] area = screen.imageBounds();
            double x = area[0] + area[2] * 0.3;
            double y = area[1] + area[3] * 0.3;
            screen.mouseScrolled(x, y, 0, 1);
            screen.mouseScrolled(x, y, 0, 1);
            return screen.zoom();
        });
        assertTrue(zoomed > 1.5f, "scrolling zooms in, zoom " + zoomed);
        context.waitTicks(2);
        context.takeScreenshot("screenshot-toolkit-viewer-zoomed");
        float fitted = context.computeOnClient(client -> {
            ShotScreen screen = (ShotScreen) client.gui.screen();
            int[] area = screen.imageBounds();
            screen.mouseClicked(new MouseButtonEvent(area[0] + 5, area[1] + 5,
                    new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)), true);
            return screen.zoom();
        });
        assertTrue(fitted == 1f, "a double click fits the image again, zoom " + fitted);
    }

    private static Shot shotFor(GalleryScreen gallery, Path file) {
        return gallery.visibleShots().stream().filter(shot -> shot.file().equals(file)).findFirst()
                .orElseThrow(() -> new AssertionError(file + " is not in the gallery"));
    }

    private static void clickAt(ClientGameTestContext context, int scale, int x, int y) {
        context.getInput().setCursorPos(x * scale, y * scale);
        context.waitTicks(2);
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTicks(2);
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
