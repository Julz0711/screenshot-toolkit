package com.julz.screenshottoolkit.client;

import com.julz.screenshottoolkit.core.ScreenshotLibrary;
import com.julz.screenshottoolkit.core.Shot;
import com.julz.screenshottoolkit.core.ShotFilter;
import com.mojang.blaze3d.Blaze3D;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** The in-game gallery: a grid of thumbnails with search and filters. */
public class GalleryScreen extends Screen {
    private static final String PREFIX = "screenshot-toolkit.gallery.";
    // Sentinel for "every world" in the world filter
    private static final String ALL_WORLDS = "";

    private final Screen parent;
    private final ScreenshotLibrary library;
    private ShotTextures thumbnails;
    private ShotFilter filter;
    private List<Shot> allShots;
    private List<Shot> visibleShots = List.of();
    private CompletableFuture<List<Shot>> loading;
    private Shot reselect;
    private boolean keepTextures;

    private ShotGrid grid;
    private Button undoButton;
    /** Screenshots deleted from the grid, newest last: where each went in the trash and its old name. */
    private final java.util.Deque<Deleted> deleted = new java.util.ArrayDeque<>();

    private record Deleted(Path trashed, String name, long lastModified) {
    }
    private EditBox search;

    public GalleryScreen(Screen parent) {
        this(parent, ShotFilter.ALL, null);
    }

    private GalleryScreen(Screen parent, ShotFilter filter, Shot reselect) {
        super(Component.translatable(PREFIX + "title"));
        this.parent = parent;
        this.library = ScreenshotToolkitClient.library();
        this.thumbnails = new ShotTextures(image -> Thumbnails.load(library, image));
        this.filter = filter;
        this.reselect = reselect;
    }

    @Override
    protected void init() {
        if (allShots == null && loading == null) {
            reload();
        }
        // Same spacing as the viewer: 12 px margins, 6 px between buttons, 10 px between sections
        int margin = 12;
        int gap = 6;
        int top = 30;
        int rowWidth = Math.min(width - 2 * margin, 540);
        int left = (width - rowWidth) / 2;
        int searchWidth = (rowWidth - 3 * gap) * 34 / 100;
        int buttonWidth = (rowWidth - 3 * gap - searchWidth) / 3;

        String previousSearch = search != null ? search.getValue() : filter.search();
        search = addRenderableWidget(new EditBox(font, left, top, searchWidth, 20, Component.translatable(PREFIX + "search")));
        search.setHint(Component.translatable(PREFIX + "search"));
        search.setValue(previousSearch == null ? "" : previousSearch);
        search.setResponder(text -> applyFilter(filter.withSearch(text)));

        int x = left + searchWidth + gap;
        addRenderableWidget(CycleButton.onOffBuilder(filter.favoritesOnly())
                .create(x, top, buttonWidth, 20, Component.translatable(PREFIX + "favorites"),
                        (button, value) -> applyFilter(filter.withFavoritesOnly(value))));
        x += buttonWidth + gap;
        List<String> worlds = new ArrayList<>();
        worlds.add(ALL_WORLDS);
        if (allShots != null) {
            worlds.addAll(ScreenshotLibrary.worlds(allShots));
        }
        String currentWorld = filter.world() != null && worlds.contains(filter.world()) ? filter.world() : ALL_WORLDS;
        addRenderableWidget(CycleButton.<String>builder(world -> world.isEmpty()
                        ? Component.translatable(PREFIX + "world.all") : Component.literal(world), currentWorld)
                .withValues(worlds)
                .create(x, top, buttonWidth, 20, Component.translatable(PREFIX + "world"),
                        (button, value) -> applyFilter(filter.withWorld(value.isEmpty() ? null : value))));
        x += buttonWidth + gap;
        addRenderableWidget(CycleButton.<ShotFilter.Period>builder(period -> Component.translatable(PREFIX + "period." + period.name().toLowerCase()), filter.period())
                .withValues(ShotFilter.Period.values())
                .create(x, top, buttonWidth, 20, Component.translatable(PREFIX + "period"),
                        (button, value) -> applyFilter(filter.withPeriod(value))));

        int footerY = height - margin - 20;
        int gridTop = top + 20 + 10;
        if (thumbnails.isClosed()) {
            // Released when another screen took over without saying it would come back
            thumbnails = new ShotTextures(image -> Thumbnails.load(library, image));
        }
        grid = addRenderableWidget(new ShotGrid(margin, gridTop, width - 2 * margin, footerY - 10 - gridTop, thumbnails, this::open, this::quickDelete));

        int footerWidth = Math.min(width - 2 * margin, 3 * 100 + 2 * gap);
        int footerButton = (footerWidth - 2 * gap) / 3;
        int footerLeft = (width - footerWidth) / 2;
        addRenderableWidget(Button.builder(Component.translatable(PREFIX + "openFolder"), button -> openFolder())
                .bounds(footerLeft, footerY, footerButton, 20).build());
        addRenderableWidget(Button.builder(Component.translatable(PREFIX + "settings"),
                        button -> {
                            keepTextures = true;
                            minecraft.gui.setScreen(new ScreenshotToolkitConfigScreen(this));
                        })
                .bounds(footerLeft + footerButton + gap, footerY, footerButton, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(footerLeft + 2 * (footerButton + gap), footerY, footerButton, 20).build());

        // Bottom left, across from the count; only there while something can be brought back
        undoButton = addRenderableWidget(Button.builder(Component.empty(), button -> undoDelete())
                .bounds(margin, footerY, Math.max(40, Math.min(90, footerLeft - margin - gap)), 20).build());
        updateUndoButton();

        refreshGrid();
        if (reselect != null && allShots != null) {
            visibleShots.stream().filter(shot -> shot.file().equals(reselect.file())).findFirst().ifPresent(grid::select);
            reselect = null;
        }
        setInitialFocus(grid);
        keepTextures = false;
    }

    /** Lists the folder in the background; the grid shows "Loading" meanwhile. */
    private void reload() {
        loading = CompletableFuture.supplyAsync(library::listUnchecked, Thumbnails.EXECUTOR);
    }

    @Override
    public void tick() {
        if (loading != null && loading.isDone()) {
            allShots = loading.exceptionally(e -> {
                ScreenshotToolkitClient.LOGGER.error("Could not list {}", library.directory(), e);
                return List.of();
            }).join();
            loading = null;
            // Rebuilt so the world filter lists the worlds just found
            rebuildWidgets();
        }
    }

    private void applyFilter(ShotFilter newFilter) {
        filter = newFilter;
        refreshGrid();
    }

    private void refreshGrid() {
        if (grid == null) {
            return;
        }
        if (allShots == null) {
            grid.setShots(List.of(), Component.translatable(PREFIX + "loading"));
            return;
        }
        long now = System.currentTimeMillis();
        visibleShots = allShots.stream().filter(shot -> filter.matches(shot, now)).toList();
        Component empty = Component.translatable(allShots.isEmpty() ? PREFIX + "empty" : PREFIX + "noMatch");
        grid.setShots(visibleShots, empty);
    }

    /** Moves a screenshot to the trash without asking; Undo brings it back. */
    private void quickDelete(Shot shot) {
        try {
            Path trashed = library.moveToTrash(shot.file());
            deleted.addLast(new Deleted(trashed, shot.name(), shot.lastModified()));
        } catch (IOException e) {
            ScreenshotToolkitClient.LOGGER.warn("Could not move {} to the trash", shot.file(), e);
            return;
        }
        allShots = allShots.stream().filter(other -> !other.file().equals(shot.file())).toList();
        refreshGrid();
        updateUndoButton();
    }

    private void undoDelete() {
        Deleted last = deleted.pollLast();
        if (last == null) {
            return;
        }
        try {
            Path restored = library.restoreFromTrash(last.trashed(), last.name());
            // The trash reset the file time; put it back so the screenshot keeps its date and place
            java.nio.file.Files.setLastModifiedTime(restored, java.nio.file.attribute.FileTime.fromMillis(last.lastModified()));
            reselect = new Shot(restored, 0, 0, null);
        } catch (IOException e) {
            ScreenshotToolkitClient.LOGGER.warn("Could not restore {}", last.trashed(), e);
        }
        updateUndoButton();
        reload();
    }

    private void updateUndoButton() {
        if (undoButton != null) {
            undoButton.visible = !deleted.isEmpty();
            undoButton.setMessage(deleted.size() > 1
                    ? Component.translatable(PREFIX + "undoMany", deleted.size())
                    : Component.translatable(PREFIX + "undo"));
        }
    }

    /** For tests. */
    public ShotGrid grid() {
        return grid;
    }

    public Button undoButton() {
        return undoButton;
    }

    private void open(Shot shot) {
        keepTextures = true;
        minecraft.gui.setScreen(new ShotScreen(this, visibleShots, visibleShots.indexOf(shot)));
    }

    /** Called by the viewer when it closes, so changes it made show up. */
    void returnFrom(Shot lastViewed, boolean changed) {
        reselect = lastViewed;
        if (changed) {
            allShots = null;
            reload();
            // A cropped-in-place screenshot keeps its name, so drop thumbnails that may show old pixels
            thumbnails.close();
        }
        minecraft.gui.setScreen(this);
    }

    private void openFolder() {
        try {
            java.nio.file.Files.createDirectories(library.directory());
            Blaze3D.openPath(library.directory());
        } catch (IOException e) {
            ScreenshotToolkitClient.LOGGER.warn("Could not open {}", library.directory(), e);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, 11, 0xFFFFFFFF);
        if (allShots != null) {
            Component count = Component.translatable(PREFIX + "count", visibleShots.size(), allShots.size());
            graphics.text(font, count, width - 12 - font.width(count), height - 12 - 15, 0xFF808080, false);
        }
    }

    public boolean isLoaded() {
        return allShots != null;
    }

    public List<Shot> visibleShots() {
        return visibleShots;
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public void removed() {
        // The viewer and settings screens come back here, so only release when really leaving
        if (!keepTextures) {
            thumbnails.close();
        }
    }
}
