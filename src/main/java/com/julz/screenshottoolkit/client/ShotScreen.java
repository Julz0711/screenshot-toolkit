package com.julz.screenshottoolkit.client;

import com.julz.screenshottoolkit.core.ScreenshotLibrary;
import com.julz.screenshottoolkit.core.Shot;
import com.julz.screenshottoolkit.core.ShotMetadata;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.Blaze3D;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * One screenshot at full size, with its details and actions: copy, star, copy /tp, crop, open,
 * delete. Left and right arrows go to the previous and next screenshot, which are loaded ahead.
 * Scrolling zooms in on the cursor, dragging pans, and a double click toggles zoom.
 */
public class ShotScreen extends Screen {
    private static final String PREFIX = "screenshot-toolkit.viewer.";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    // Layout, in GUI pixels
    private static final int MARGIN = 12;
    private static final int TITLE_HEIGHT = 30;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 6;
    private static final int BUTTON_WIDTH = 84;
    private static final int ARROW_WIDTH = 24;
    private static final int LINE_HEIGHT = 12;
    private static final int TEXT_HEIGHT = 9;
    private static final int SECTION_GAP = 10;

    private final GalleryScreen gallery;
    private final ScreenshotLibrary library;
    private final List<Shot> shots;
    // The screenshot on screen and its two neighbours
    private final ShotTextures images = new ShotTextures(Thumbnails::loadFull, 3);
    private static final float MAX_ZOOM = 8;
    private static final float ZOOM_STEP = 1.25f;
    private static final float DOUBLE_CLICK_ZOOM = 2.5f;
    private int index;
    private boolean changed;

    // Crop selection in image pixels; null when not cropping
    private boolean cropping;
    private boolean selecting;
    private int[] cropStart;
    private int[] cropEnd;

    private int lastMouseX;
    private int lastMouseY;

    // Zoom over the fitted size, and the image point (0 to 1) at the middle of the view
    private float zoom = 1;
    private double centerX = 0.5;
    private double centerY = 0.5;
    private boolean panning;
    private double panLastX;
    private double panLastY;

    private Button favoriteButton;
    private Button teleportButton;
    private Button cropButton;
    private Button renameButton;
    private Button backButton;

    public ShotScreen(GalleryScreen gallery, List<Shot> shots, int index) {
        super(Component.translatable(PREFIX + "title"));
        this.gallery = gallery;
        this.library = ScreenshotToolkitClient.library();
        this.shots = new ArrayList<>(shots);
        this.index = Mth.clamp(index, 0, shots.size() - 1);
    }

    private Shot shot() {
        return shots.get(index);
    }

    @Override
    protected void init() {
        // Two rows of equal width: the actions on top, browsing below
        int rowWidth = Math.min(width - 2 * MARGIN, 5 * BUTTON_WIDTH + 4 * BUTTON_GAP);
        int left = (width - rowWidth) / 2;
        int row2 = buttonsTop() + BUTTON_HEIGHT + BUTTON_GAP;
        int row1 = buttonsTop();

        int action = (rowWidth - 4 * BUTTON_GAP) / 5;
        int x = left;
        addRenderableWidget(Button.builder(Component.translatable(PREFIX + "copy"), b -> copy())
                .bounds(x, row1, action, BUTTON_HEIGHT).build());
        x += action + BUTTON_GAP;
        favoriteButton = addRenderableWidget(Button.builder(Component.empty(), b -> toggleFavorite())
                .bounds(x, row1, action, BUTTON_HEIGHT).build());
        x += action + BUTTON_GAP;
        teleportButton = addRenderableWidget(Button.builder(Component.translatable(PREFIX + "teleport"), b -> copyTeleport())
                .bounds(x, row1, action, BUTTON_HEIGHT).build());
        x += action + BUTTON_GAP;
        renameButton = addRenderableWidget(Button.builder(Component.translatable(PREFIX + "rename"), b -> openRename())
                .bounds(x, row1, action, BUTTON_HEIGHT).build());
        // The last button takes the rounding leftovers so both rows end at the same edge
        x += action + BUTTON_GAP;
        cropButton = addRenderableWidget(Button.builder(Component.translatable(PREFIX + "crop"), b -> cropPressed())
                .bounds(x, row1, left + rowWidth - x, BUTTON_HEIGHT).build());

        addRenderableWidget(Button.builder(Component.literal("<"), b -> move(-1))
                .bounds(left, row2, ARROW_WIDTH, BUTTON_HEIGHT).build());
        int middle = (rowWidth - 2 * ARROW_WIDTH - 4 * BUTTON_GAP) / 3;
        x = left + ARROW_WIDTH + BUTTON_GAP;
        addRenderableWidget(Button.builder(Component.translatable(PREFIX + "open"), b -> openFile())
                .bounds(x, row2, middle, BUTTON_HEIGHT).build());
        x += middle + BUTTON_GAP;
        addRenderableWidget(Button.builder(Component.translatable(PREFIX + "delete"), b -> confirmDelete())
                .bounds(x, row2, middle, BUTTON_HEIGHT).build());
        x += middle + BUTTON_GAP;
        int lastArrow = left + rowWidth - ARROW_WIDTH;
        backButton = addRenderableWidget(Button.builder(Component.translatable(PREFIX + "back"), b -> onClose())
                .bounds(x, row2, lastArrow - BUTTON_GAP - x, BUTTON_HEIGHT).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> move(1))
                .bounds(lastArrow, row2, ARROW_WIDTH, BUTTON_HEIGHT).build());

        updateButtons();
    }

    private void updateButtons() {
        Shot shot = shot();
        favoriteButton.setMessage(Component.translatable(shot.favorite() ? PREFIX + "unstar" : PREFIX + "star"));
        teleportButton.active = shot.metadata() != null && shot.metadata().hasPosition();
        // While cropping, Crop turns into Save Crop and Back into Cancel
        cropButton.setMessage(Component.translatable(cropping ? PREFIX + "saveCrop" : PREFIX + "crop"));
        cropButton.active = !cropping || cropRect() != null;
        renameButton.active = !cropping;
        backButton.setMessage(Component.translatable(cropping ? PREFIX + "cancelCrop" : PREFIX + "back"));
    }

    private void move(int delta) {
        int next = Mth.clamp(index + delta, 0, shots.size() - 1);
        if (next != index) {
            index = next;
            resetZoom();
            cropping = false;
            selecting = false;
            cropStart = cropEnd = null;
            updateButtons();
        }
    }

    private void copy() {
        ScreenshotToolkitClient.copyToClipboard(minecraft, shot().file(), null);
    }

    private void toggleFavorite() {
        Shot shot = shot();
        try {
            ShotMetadata metadata = library.setFavorite(shot.file(), !shot.favorite());
            shots.set(index, new Shot(shot.file(), shot.lastModified(), shot.size(), metadata));
            changed = true;
        } catch (IOException e) {
            ScreenshotToolkitClient.LOGGER.warn("Could not save favorite for {}", shot.file(), e);
        }
        updateButtons();
    }

    private void copyTeleport() {
        String command = shot().metadata() != null ? shot().metadata().teleportCommand() : null;
        if (command != null) {
            minecraft.keyboardHandler.setClipboard(command);
            SystemToast.addOrUpdate(minecraft.gui.toastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                    Component.translatable(PREFIX + "teleportCopied"), Component.literal(command));
        }
    }

    private void openRename() {
        minecraft.gui.setScreen(new RenameScreen(this, library, shot().file(), this::renamed));
    }

    private void renamed(Path file) {
        Shot old = shot();
        images.forget(old.file());
        shots.set(index, new Shot(file, old.lastModified(), old.size(), library.readMetadata(file)));
        changed = true;
    }

    /** Top of the two button rows. */
    private int buttonsTop() {
        return height - MARGIN - 2 * BUTTON_HEIGHT - BUTTON_GAP;
    }

    /** Top of the two lines of details above the buttons. */
    private int infoTop() {
        return buttonsTop() - SECTION_GAP - 2 * LINE_HEIGHT;
    }

    private void openFile() {
        Blaze3D.openPath(shot().file());
    }

    private void confirmDelete() {
        Shot shot = shot();
        minecraft.gui.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                delete(shot);
            }
            minecraft.gui.setScreen(this);
        }, Component.translatable(PREFIX + "deleteTitle"),
                Component.translatable(PREFIX + "deleteMessage", shot.name(), ScreenshotToolkitClient.config().trashDays)));
    }

    private void delete(Shot shot) {
        try {
            library.moveToTrash(shot.file());
        } catch (IOException e) {
            ScreenshotToolkitClient.LOGGER.warn("Could not move {} to the trash", shot.file(), e);
            return;
        }
        changed = true;
        images.forget(shot.file());
        shots.remove(shot);
        if (shots.isEmpty()) {
            images.close();
            gallery.returnFrom(null, true);
            return;
        }
        index = Math.min(index, shots.size() - 1);
    }

    // ---- Crop ----

    private void cropPressed() {
        if (cropping) {
            saveCrop();
        } else {
            toggleCrop();
        }
    }

    private void toggleCrop() {
        // Cropping works on the whole image
        resetZoom();
        cropping = !cropping;
        selecting = false;
        cropStart = cropEnd = null;
        updateButtons();
    }

    private boolean overImage(double mouseX, double mouseY) {
        ShotTextures.Texture texture = images.get(shot().file());
        if (texture == null) {
            return false;
        }
        int[] area = imageArea(texture);
        return mouseX >= area[0] && mouseX < area[0] + area[2] && mouseY >= area[1] && mouseY < area[1] + area[3];
    }

    /** Where the image is drawn on screen: x, y, width, height. */
    private int[] imageArea(ShotTextures.Texture texture) {
        int areaTop = TITLE_HEIGHT;
        int areaHeight = infoTop() - SECTION_GAP - areaTop;
        int areaWidth = width - 4 * MARGIN;
        float scale = Math.min(areaWidth / (float) texture.width(), areaHeight / (float) texture.height());
        int w = Math.max(1, Math.round(texture.width() * scale));
        int h = Math.max(1, Math.round(texture.height() * scale));
        return new int[]{(width - w) / 2, areaTop + (areaHeight - h) / 2, w, h};
    }

    // ---- Zoom ----

    private void resetZoom() {
        zoom = 1;
        centerX = centerY = 0.5;
        panning = false;
    }

    /** Where the zoomed image is drawn: x, y, width, height. Same as the fitted area at zoom 1. */
    private double[] zoomedRect(int[] area) {
        double w = area[2] * zoom;
        double h = area[3] * zoom;
        double middleX = area[0] + area[2] / 2.0;
        double middleY = area[1] + area[3] / 2.0;
        return new double[]{middleX - centerX * w, middleY - centerY * h, w, h};
    }

    /** Keeps the view inside the image, so zooming never shows empty space. */
    private void clampCenter() {
        double half = 0.5 / zoom;
        centerX = Mth.clamp(centerX, half, 1 - half);
        centerY = Mth.clamp(centerY, half, 1 - half);
    }

    /** Zooms by a factor, keeping the image point under the cursor where it is. */
    private void zoomAt(double mouseX, double mouseY, float factor) {
        ShotTextures.Texture texture = images.get(shot().file());
        if (texture == null) {
            return;
        }
        int[] area = imageArea(texture);
        double[] before = zoomedRect(area);
        double u = (mouseX - before[0]) / before[2];
        double v = (mouseY - before[1]) / before[3];
        zoom = Mth.clamp(zoom * factor, 1, MAX_ZOOM);
        centerX = u - (mouseX - (area[0] + area[2] / 2.0)) / (area[2] * zoom);
        centerY = v - (mouseY - (area[1] + area[3] / 2.0)) / (area[3] * zoom);
        clampCenter();
    }

    public float zoom() {
        return zoom;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!cropping && scrollY != 0 && overImage(mouseX, mouseY)) {
            zoomAt(mouseX, mouseY, scrollY > 0 ? ZOOM_STEP : 1 / ZOOM_STEP);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void pan(double mouseX, double mouseY) {
        ShotTextures.Texture texture = images.get(shot().file());
        if (texture == null) {
            return;
        }
        int[] area = imageArea(texture);
        centerX -= (mouseX - panLastX) / (area[2] * zoom);
        centerY -= (mouseY - panLastY) / (area[3] * zoom);
        panLastX = mouseX;
        panLastY = mouseY;
        clampCenter();
    }

    /** Screen position to image pixel, clamped to the image. */
    private int[] toImage(double mouseX, double mouseY) {
        ShotTextures.Texture texture = images.get(shot().file());
        if (texture == null) {
            return null;
        }
        int[] area = imageArea(texture);
        int px = (int) Math.round((mouseX - area[0]) * texture.width() / area[2]);
        int py = (int) Math.round((mouseY - area[1]) * texture.height() / area[3]);
        return new int[]{Mth.clamp(px, 0, texture.width()), Mth.clamp(py, 0, texture.height())};
    }

    /** The selection as x, y, width, height in image pixels, or null when it is too small. */
    private int[] cropRect() {
        if (cropStart == null || cropEnd == null) {
            return null;
        }
        int x = Math.min(cropStart[0], cropEnd[0]);
        int y = Math.min(cropStart[1], cropEnd[1]);
        int w = Math.abs(cropStart[0] - cropEnd[0]);
        int h = Math.abs(cropStart[1] - cropEnd[1]);
        return w >= 8 && h >= 8 ? new int[]{x, y, w, h} : null;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // The image never overlaps a button, so a click on it always starts a selection
        if (cropping && event.button() == InputConstants.MOUSE_BUTTON_LEFT && overImage(event.x(), event.y())) {
            cropStart = toImage(event.x(), event.y());
            cropEnd = cropStart;
            selecting = true;
            updateButtons();
            return true;
        }
        if (!cropping && event.button() == InputConstants.MOUSE_BUTTON_LEFT && overImage(event.x(), event.y())) {
            if (doubleClick) {
                if (zoom > 1) {
                    resetZoom();
                } else {
                    zoomAt(event.x(), event.y(), DOUBLE_CLICK_ZOOM);
                }
            } else if (zoom > 1) {
                panning = true;
                panLastX = event.x();
                panLastY = event.y();
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (selecting) {
            extendSelection(event.x(), event.y());
            return true;
        }
        if (panning) {
            pan(event.x(), event.y());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        // Drag events only reach a screen in some cases, so follow the cursor while the button is held
        if (selecting) {
            extendSelection(mouseX, mouseY);
        }
        if (panning) {
            pan(mouseX, mouseY);
        }
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (selecting && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            extendSelection(event.x(), event.y());
            selecting = false;
            return true;
        }
        if (panning && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            panning = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    private void extendSelection(double mouseX, double mouseY) {
        int[] point = toImage(mouseX, mouseY);
        if (point != null) {
            cropEnd = point;
            updateButtons();
        }
    }

    private void saveCrop() {
        int[] rect = cropRect();
        if (rect == null) {
            return;
        }
        minecraft.gui.setScreen(new CropChoiceScreen(this, ScreenshotToolkitClient.config().trashDays,
                () -> saveCropAsNew(rect), () -> replaceWithCrop(rect)));
    }

    private void saveCropAsNew(int[] rect) {
        Shot shot = shot();
        try {
            Path cropped = Crop.save(library, shot.file(), rect[0], rect[1], rect[2], rect[3]);
            ShotMetadata metadata = library.readMetadata(cropped);
            // Show the new file right away; it sits before the original, like in the gallery
            images.forget(shot.file());
            shots.add(index, new Shot(cropped, System.currentTimeMillis(), 0, metadata));
            changed = true;
            SystemToast.addOrUpdate(minecraft.gui.toastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                    Component.translatable(PREFIX + "cropSaved"), Component.literal(cropped.getFileName().toString()));
        } catch (IOException e) {
            ScreenshotToolkitClient.LOGGER.warn("Could not crop {}", shot.file(), e);
        }
        endCrop();
    }

    private void replaceWithCrop(int[] rect) {
        Shot shot = shot();
        try {
            Crop.replace(library, shot.file(), rect[0], rect[1], rect[2], rect[3]);
            // Load the cropped pixels instead of the cached original
            images.forget(shot.file());
            changed = true;
            SystemToast.addOrUpdate(minecraft.gui.toastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                    Component.translatable(PREFIX + "cropReplaced"), Component.literal(shot.name()));
        } catch (IOException e) {
            ScreenshotToolkitClient.LOGGER.warn("Could not crop {}", shot.file(), e);
        }
        endCrop();
    }

    private void endCrop() {
        cropping = false;
        selecting = false;
        cropStart = cropEnd = null;
        updateButtons();
    }

    // ---- Drawing ----

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        images.upload();
        Shot shot = shot();
        graphics.centeredText(font, cropping ? Component.translatable(PREFIX + "cropHint") : Component.literal(shot.name()),
                width / 2, (TITLE_HEIGHT - TEXT_HEIGHT) / 2, 0xFFFFFFFF);

        ShotTextures.Texture texture = images.get(shot.file());
        if (texture != null) {
            int[] area = imageArea(texture);
            // A thin frame so dark screenshots don't melt into the background
            graphics.outline(area[0] - 1, area[1] - 1, area[2] + 2, area[3] + 2, 0x40FFFFFF);
            if (zoom > 1) {
                double[] view = zoomedRect(area);
                graphics.enableScissor(area[0], area[1], area[0] + area[2], area[1] + area[3]);
                graphics.blit(RenderPipelines.GUI_TEXTURED, texture.id(), (int) Math.round(view[0]), (int) Math.round(view[1]),
                        0, 0, (int) Math.round(view[2]), (int) Math.round(view[3]),
                        texture.width(), texture.height(), texture.width(), texture.height());
                graphics.disableScissor();
                graphics.text(font, Math.round(zoom * 100) + "%", area[0] + 4, area[1] + 4, 0xFFFFFFFF, true);
            } else {
                ShotGrid.drawFitted(graphics, texture, area[0], area[1], area[2], area[3]);
            }
            // Load the neighbours once this one is in, so arrowing through is instant
            if (index > 0) {
                images.prefetch(shots.get(index - 1).file());
            }
            if (index < shots.size() - 1) {
                images.prefetch(shots.get(index + 1).file());
            }
            int[] rect = cropRect();
            if (cropping && cropStart != null && cropEnd != null) {
                int x1 = area[0] + Math.min(cropStart[0], cropEnd[0]) * area[2] / texture.width();
                int y1 = area[1] + Math.min(cropStart[1], cropEnd[1]) * area[3] / texture.height();
                int x2 = area[0] + Math.max(cropStart[0], cropEnd[0]) * area[2] / texture.width();
                int y2 = area[1] + Math.max(cropStart[1], cropEnd[1]) * area[3] / texture.height();
                // Darken everything outside the selection
                graphics.fill(area[0], area[1], area[0] + area[2], y1, 0x99000000);
                graphics.fill(area[0], y2, area[0] + area[2], area[1] + area[3], 0x99000000);
                graphics.fill(area[0], y1, x1, y2, 0x99000000);
                graphics.fill(x2, y1, area[0] + area[2], y2, 0x99000000);
                graphics.outline(x1, y1, Math.max(1, x2 - x1), Math.max(1, y2 - y1), rect != null ? 0xFFFFFFFF : 0xFFFF5555);
                if (rect != null) {
                    graphics.text(font, rect[2] + " x " + rect[3], x1 + 3, y1 + 3, 0xFFFFFFFF, true);
                }
            }
        } else {
            Component status = Component.translatable(images.failed(shot.file())
                    ? "screenshot-toolkit.gallery.unreadable" : "screenshot-toolkit.gallery.loading");
            graphics.centeredText(font, status, width / 2, height / 2 - 20, 0xFF808080);
        }

        int infoY = infoTop() + (LINE_HEIGHT - TEXT_HEIGHT) / 2;
        graphics.centeredText(font, detailsLine(shot), width / 2, infoY, 0xFFC0C0C0);
        Component position = Component.translatable(PREFIX + "position", index + 1, shots.size());
        if (!cropping) {
            position = Component.translatable(PREFIX + "positionHint", position, Component.translatable(zoom > 1 ? PREFIX + "panHint" : PREFIX + "zoomHint"));
        }
        graphics.centeredText(font, position, width / 2, infoY + LINE_HEIGHT, 0xFF808080);
    }

    /** "Survival World · Overworld · 120, 64, -30 · day · 2026-10-19 19:30:22" */
    private Component detailsLine(Shot shot) {
        List<String> parts = new ArrayList<>();
        ShotMetadata metadata = shot.metadata();
        if (metadata != null) {
            if (metadata.world != null) {
                parts.add(metadata.world);
            }
            if (metadata.dimensionName() != null) {
                parts.add(metadata.dimensionName());
            }
            if (metadata.hasPosition()) {
                parts.add(Math.round(metadata.x) + ", " + Math.round(metadata.y) + ", " + Math.round(metadata.z));
            }
            if (metadata.dayTime != null) {
                long time = Math.floorMod(metadata.dayTime, 24000L);
                parts.add(Component.translatable(time < 12542 || time > 23460 ? PREFIX + "day" : PREFIX + "night").getString());
            }
        }
        parts.add(DATE.format(Instant.ofEpochMilli(shot.takenAt())));
        if (!shot.albums().isEmpty()) {
            parts.add(String.join(", ", shot.albums()));
        }
        return Component.literal(String.join("  ·  ", parts));
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!cropping) {
            if (event.isLeft()) {
                move(-1);
                return true;
            }
            if (event.isRight()) {
                move(1);
                return true;
            }
            if (event.key() == InputConstants.KEY_DELETE) {
                confirmDelete();
                return true;
            }
        }
        return super.keyPressed(event);
    }

    /** For tests: the crop button, the back button and where the image is drawn. */
    public Button cropButton() {
        return cropButton;
    }

    public Button renameButton() {
        return renameButton;
    }

    public Button backButton() {
        return backButton;
    }

    public int[] imageBounds() {
        ShotTextures.Texture texture = images.get(shot().file());
        return texture != null ? imageArea(texture) : null;
    }

    public Path currentFile() {
        return shot().file();
    }

    /** Where the screen last saw the mouse, in GUI pixels. */
    public int[] lastMouse() {
        return new int[]{lastMouseX, lastMouseY};
    }

    public boolean isCropping() {
        return cropping;
    }

    public int[] cropSelection() {
        return cropRect();
    }

    @Override
    public void onClose() {
        if (cropping) {
            toggleCrop();
            return;
        }
        images.close();
        gallery.returnFrom(shot(), changed);
    }
}
