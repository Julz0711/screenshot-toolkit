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
 * delete. Left and right arrows go to the previous and next screenshot.
 */
public class ShotScreen extends Screen {
    private static final String PREFIX = "screenshot-toolkit.viewer.";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final int INFO_HEIGHT = 24;
    private static final int BUTTONS_HEIGHT = 52;

    private final GalleryScreen gallery;
    private final ScreenshotLibrary library;
    private final List<Shot> shots;
    private final ShotTextures images = new ShotTextures(Thumbnails::loadFull);
    private int index;
    private boolean changed;

    // Crop selection in image pixels; null when not cropping
    private boolean cropping;
    private int[] cropStart;
    private int[] cropEnd;

    private Button favoriteButton;
    private Button teleportButton;
    private Button cropButton;
    private Button saveCropButton;

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
        int gap = 4;
        int rowWidth = Math.min(width - 20, 4 * 90 + 3 * gap);
        int button = (rowWidth - 3 * gap) / 4;
        int left = (width - rowWidth) / 2;
        int row1 = height - BUTTONS_HEIGHT;
        int row2 = row1 + 24;

        addRenderableWidget(Button.builder(Component.translatable(PREFIX + "copy"), b -> copy())
                .bounds(left, row1, button, 20).build());
        favoriteButton = addRenderableWidget(Button.builder(Component.empty(), b -> toggleFavorite())
                .bounds(left + (button + gap), row1, button, 20).build());
        teleportButton = addRenderableWidget(Button.builder(Component.translatable(PREFIX + "teleport"), b -> copyTeleport())
                .bounds(left + 2 * (button + gap), row1, button, 20).build());
        cropButton = addRenderableWidget(Button.builder(Component.translatable(PREFIX + "crop"), b -> toggleCrop())
                .bounds(left + 3 * (button + gap), row1, button, 20).build());

        addRenderableWidget(Button.builder(Component.literal("<"), b -> move(-1))
                .bounds(left, row2, 20, 20).build());
        int middle = (rowWidth - 2 * 20 - 4 * gap) / 3;
        int x = left + 20 + gap;
        addRenderableWidget(Button.builder(Component.translatable(PREFIX + "open"), b -> openFile())
                .bounds(x, row2, middle, 20).build());
        x += middle + gap;
        addRenderableWidget(Button.builder(Component.translatable(PREFIX + "delete"), b -> confirmDelete())
                .bounds(x, row2, middle, 20).build());
        x += middle + gap;
        addRenderableWidget(Button.builder(Component.translatable(PREFIX + "back"), b -> onClose())
                .bounds(x, row2, middle, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> move(1))
                .bounds(left + rowWidth - 20, row2, 20, 20).build());

        saveCropButton = addRenderableWidget(Button.builder(Component.translatable(PREFIX + "saveCrop"), b -> saveCrop())
                .bounds(width - 110, 4, 100, 20).build());
        updateButtons();
    }

    private void updateButtons() {
        Shot shot = shot();
        favoriteButton.setMessage(Component.translatable(shot.favorite() ? PREFIX + "unstar" : PREFIX + "star"));
        teleportButton.active = shot.metadata() != null && shot.metadata().hasPosition();
        cropButton.setMessage(Component.translatable(cropping ? PREFIX + "cancelCrop" : PREFIX + "crop"));
        saveCropButton.visible = cropping;
        saveCropButton.active = cropping && cropRect() != null;
    }

    private void move(int delta) {
        int next = Mth.clamp(index + delta, 0, shots.size() - 1);
        if (next != index) {
            images.forget(shot().file());
            index = next;
            cropping = false;
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

    private void toggleCrop() {
        cropping = !cropping;
        cropStart = cropEnd = null;
        updateButtons();
    }

    /** Where the image is drawn on screen: x, y, width, height. */
    private int[] imageArea(ShotTextures.Texture texture) {
        int areaTop = 28;
        int areaHeight = height - areaTop - BUTTONS_HEIGHT - INFO_HEIGHT;
        int areaWidth = width - 20;
        float scale = Math.min(areaWidth / (float) texture.width(), areaHeight / (float) texture.height());
        int w = Math.max(1, Math.round(texture.width() * scale));
        int h = Math.max(1, Math.round(texture.height() * scale));
        return new int[]{(width - w) / 2, areaTop + (areaHeight - h) / 2, w, h};
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
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (cropping && event.button() == 0) {
            cropStart = toImage(event.x(), event.y());
            cropEnd = cropStart;
            updateButtons();
            return cropStart != null;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (cropping && cropStart != null) {
            cropEnd = toImage(event.x(), event.y());
            updateButtons();
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    private void saveCrop() {
        int[] rect = cropRect();
        if (rect == null) {
            return;
        }
        Shot shot = shot();
        try {
            Path cropped = Crop.save(library, shot.file(), rect[0], rect[1], rect[2], rect[3]);
            ShotMetadata metadata = library.readMetadata(cropped);
            shots.add(index, new Shot(cropped, System.currentTimeMillis(), 0, metadata));
            changed = true;
            SystemToast.addOrUpdate(minecraft.gui.toastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                    Component.translatable(PREFIX + "cropSaved"), Component.literal(cropped.getFileName().toString()));
        } catch (IOException e) {
            ScreenshotToolkitClient.LOGGER.warn("Could not crop {}", shot.file(), e);
        }
        cropping = false;
        cropStart = cropEnd = null;
        updateButtons();
    }

    // ---- Drawing ----

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        images.upload();
        Shot shot = shot();
        graphics.centeredText(font, cropping ? Component.translatable(PREFIX + "cropHint") : Component.literal(shot.name()),
                width / 2, 10, 0xFFFFFFFF);

        ShotTextures.Texture texture = images.get(shot.file());
        if (texture != null) {
            int[] area = imageArea(texture);
            ShotGrid.drawFitted(graphics, texture, area[0], area[1], area[2], area[3]);
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

        int infoY = height - BUTTONS_HEIGHT - INFO_HEIGHT + 2;
        graphics.centeredText(font, detailsLine(shot), width / 2, infoY, 0xFFC0C0C0);
        graphics.centeredText(font, Component.translatable(PREFIX + "position", index + 1, shots.size()),
                width / 2, infoY + 11, 0xFF808080);
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
