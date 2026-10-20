package com.julz.screenshottoolkit.client;

import com.julz.screenshottoolkit.core.ScreenshotLibrary;
import com.julz.screenshottoolkit.core.Shot;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Deleted screenshots, with how long each has left before the trash removes it. Check some to
 * restore them or delete them for good, or empty the whole trash.
 */
public class TrashScreen extends Screen {
    private static final String PREFIX = "screenshot-toolkit.trash.";

    private final GalleryScreen gallery;
    private final ScreenshotLibrary library;
    private final ShotTextures thumbnails = new ShotTextures(Thumbnails::loadUncached, 200);
    private List<Shot> shots;
    private CompletableFuture<List<Shot>> loading;
    private boolean changed;

    private ShotGrid grid;
    private Button restoreButton;
    private Button deleteButton;
    private Button emptyButton;

    public TrashScreen(GalleryScreen gallery) {
        super(Component.translatable(PREFIX + "title"));
        this.gallery = gallery;
        this.library = ScreenshotToolkitClient.library();
    }

    @Override
    protected void init() {
        if (shots == null && loading == null) {
            reload();
        }
        int margin = 12;
        int gap = 6;
        int gridTop = 44;
        int footerY = height - margin - 20;
        int days = ScreenshotToolkitClient.config().trashDays;
        grid = addRenderableWidget(new ShotGrid(margin, gridTop, width - 2 * margin, footerY - 10 - gridTop, thumbnails,
                shot -> { }, this::confirmDelete, this::updateButtons)
                .quickDelete(false)
                .clickChecks(true)
                .labels(shot -> {
                    int left = ScreenshotLibrary.daysLeft(shot.lastModified(), days, System.currentTimeMillis());
                    return left <= 1 ? Component.translatable(PREFIX + "lastDay").getString()
                            : Component.translatable(PREFIX + "daysLeft", left).getString();
                }));

        int footerWidth = Math.min(width - 2 * margin, 4 * 100 + 3 * gap);
        int button = (footerWidth - 3 * gap) / 4;
        int x = (width - footerWidth) / 2;
        restoreButton = addRenderableWidget(Button.builder(Component.empty(), b -> restore(grid.checkedShots()))
                .bounds(x, footerY, button, 20).build());
        x += button + gap;
        deleteButton = addRenderableWidget(Button.builder(Component.empty(), b -> confirmDelete(grid.checkedShots()))
                .bounds(x, footerY, button, 20).build());
        x += button + gap;
        emptyButton = addRenderableWidget(Button.builder(Component.translatable(PREFIX + "empty"), b -> confirmDelete(shots))
                .bounds(x, footerY, button, 20).build());
        x += button + gap;
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
                .bounds(x, footerY, button, 20).build());
        refreshGrid();
        setInitialFocus(grid);
    }

    private void reload() {
        loading = CompletableFuture.supplyAsync(() -> {
            try {
                return library.listTrash();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }, Thumbnails.EXECUTOR);
    }

    @Override
    public void tick() {
        if (loading != null && loading.isDone()) {
            shots = loading.exceptionally(e -> {
                ScreenshotToolkitClient.LOGGER.error("Could not list {}", library.trashDirectory(), e);
                return List.of();
            }).join();
            loading = null;
            refreshGrid();
        }
    }

    private void refreshGrid() {
        if (grid == null) {
            return;
        }
        grid.setShots(shots == null ? List.of() : shots, Component.translatable(shots == null
                ? "screenshot-toolkit.gallery.loading" : PREFIX + "nothing"));
        updateButtons();
    }

    private void updateButtons() {
        if (emptyButton == null) {
            return;
        }
        int count = grid.checkedShots().size();
        restoreButton.setMessage(Component.translatable(PREFIX + "restore", count));
        deleteButton.setMessage(Component.translatable(PREFIX + "delete", count));
        restoreButton.active = count > 0;
        deleteButton.active = count > 0;
        emptyButton.active = shots != null && !shots.isEmpty();
    }

    /** Puts screenshots back in the folder under the name they had in the trash. */
    private void restore(List<Shot> chosen) {
        for (Shot shot : chosen) {
            try {
                library.restoreFromTrash(shot.file(), shot.name());
                changed = true;
            } catch (IOException e) {
                ScreenshotToolkitClient.LOGGER.warn("Could not restore {}", shot.file(), e);
            }
        }
        removeFromList(chosen);
    }

    private void confirmDelete(List<Shot> chosen) {
        if (chosen == null || chosen.isEmpty()) {
            return;
        }
        List<Shot> targets = List.copyOf(chosen);
        minecraft.gui.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                deleteForever(targets);
            }
            minecraft.gui.setScreen(this);
        }, Component.translatable(PREFIX + "confirmTitle", targets.size()), Component.translatable(PREFIX + "confirmMessage")));
    }

    private void deleteForever(List<Shot> targets) {
        for (Shot shot : targets) {
            try {
                library.deleteForever(shot.file());
            } catch (IOException e) {
                ScreenshotToolkitClient.LOGGER.warn("Could not delete {}", shot.file(), e);
            }
        }
        removeFromList(targets);
    }

    private void removeFromList(List<Shot> gone) {
        List<Path> files = gone.stream().map(Shot::file).toList();
        files.forEach(thumbnails::forget);
        shots = shots.stream().filter(shot -> !files.contains(shot.file()) && Files.exists(shot.file())).toList();
        grid.clearChecked();
        refreshGrid();
    }

    // Presses that start on the grid follow the mouse even outside it, like the crop selection
    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        if (grid != null && grid.isPressing()) {
            grid.dragTo(mouseX, mouseY);
        }
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (grid != null && grid.isPressing() && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            grid.release(event.x(), event.y());
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_ESCAPE && grid != null && grid.isChecking()) {
            grid.clearChecked();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        Component heading = grid != null && grid.isChecking()
                ? Component.translatable("screenshot-toolkit.gallery.checked", grid.checkedShots().size()) : title;
        graphics.centeredText(font, heading, width / 2, 11, 0xFFFFFFFF);
        graphics.centeredText(font, Component.translatable(PREFIX + "hint", ScreenshotToolkitClient.config().trashDays),
                width / 2, 27, 0xFF909090);
    }

    /** For tests. */
    public ShotGrid grid() {
        return grid;
    }

    public Button restoreButton() {
        return restoreButton;
    }

    public boolean isLoaded() {
        return shots != null && loading == null;
    }

    @Override
    public void onClose() {
        thumbnails.close();
        gallery.returnFromTrash(changed);
    }
}
