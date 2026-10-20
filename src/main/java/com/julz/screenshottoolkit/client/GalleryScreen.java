package com.julz.screenshottoolkit.client;

import com.julz.screenshottoolkit.core.ScreenshotLibrary;
import com.julz.screenshottoolkit.core.Shot;
import com.julz.screenshottoolkit.core.ShotFilter;
import com.julz.screenshottoolkit.core.ShotMetadata;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.Blaze3D;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** The in-game gallery: a grid of thumbnails with search and filters. */
public class GalleryScreen extends Screen {
    private static final String PREFIX = "screenshot-toolkit.gallery.";
    // Sentinel for "every world" in the world filter
    private static final String ALL_WORLDS = "";
    // Thumbnails kept on the GPU; a few screens full at the biggest size, about 40 MB
    private static final int THUMBNAIL_CAPACITY = 300;

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
    private Button trashButton;
    private Button albumsButton;
    // The footer while nothing is checked, and the bulk actions that replace it while something is
    private final List<Button> normalFooter = new ArrayList<>();
    private final List<Button> bulkFooter = new ArrayList<>();
    private Button bulkStarButton;
    private Button bulkDeleteButton;
    private Button bulkAlbumButton;
    /** Deletes from the grid, newest last; each is one click or key press, so Undo brings back a whole batch. */
    private final Deque<List<Deleted>> deleted = new ArrayDeque<>();

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
        this.thumbnails = new ShotTextures(image -> Thumbnails.load(library, image), THUMBNAIL_CAPACITY);
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

        // Top right, across from the title, like the bin in a photo app
        // Top left, across from Trash
        albumsButton = addRenderableWidget(Button.builder(Component.translatable(PREFIX + "albums"), button -> openAlbums(List.of()))
                .bounds(margin, 5, 60, 20).build());
        trashButton = addRenderableWidget(Button.builder(Component.translatable(PREFIX + "trash"), button -> openTrash())
                .bounds(width - margin - 60, 5, 60, 20).build());

        int footerY = height - margin - 20;
        int gridTop = top + 20 + 10;
        if (thumbnails.isClosed()) {
            // Released when another screen took over without saying it would come back
            thumbnails = new ShotTextures(image -> Thumbnails.load(library, image), THUMBNAIL_CAPACITY);
        }
        grid = addRenderableWidget(new ShotGrid(margin, gridTop, width - 2 * margin, footerY - 10 - gridTop, thumbnails,
                this::open, this::quickDelete, this::updateFooter));

        int footerWidth = Math.min(width - 2 * margin, 3 * 100 + 2 * gap);
        int footerButton = (footerWidth - 2 * gap) / 3;
        int footerLeft = (width - footerWidth) / 2;
        normalFooter.clear();
        normalFooter.add(addRenderableWidget(Button.builder(Component.translatable(PREFIX + "openFolder"), button -> openFolder())
                .bounds(footerLeft, footerY, footerButton, 20).build()));
        normalFooter.add(addRenderableWidget(Button.builder(Component.translatable(PREFIX + "settings"),
                        button -> {
                            keepTextures = true;
                            minecraft.gui.setScreen(new ScreenshotToolkitConfigScreen(this));
                        })
                .bounds(footerLeft + footerButton + gap, footerY, footerButton, 20).build()));
        normalFooter.add(addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(footerLeft + 2 * (footerButton + gap), footerY, footerButton, 20).build()));

        // Bulk actions take the whole footer row while screenshots are checked
        int bulkWidth = Math.min(width - 2 * margin, 5 * 90 + 4 * gap);
        int bulkButton = (bulkWidth - 4 * gap) / 5;
        int bulkLeft = (width - bulkWidth) / 2;
        bulkFooter.clear();
        bulkFooter.add(addRenderableWidget(Button.builder(Component.translatable(PREFIX + "selectAll"), button -> grid.checkAll())
                .bounds(bulkLeft, footerY, bulkButton, 20).build()));
        bulkStarButton = addRenderableWidget(Button.builder(Component.empty(), button -> starChecked())
                .bounds(bulkLeft + bulkButton + gap, footerY, bulkButton, 20).build());
        bulkFooter.add(bulkStarButton);
        bulkAlbumButton = addRenderableWidget(Button.builder(Component.translatable(PREFIX + "addToAlbum"), button -> openAlbums(grid.checkedShots()))
                .bounds(bulkLeft + 2 * (bulkButton + gap), footerY, bulkButton, 20).build());
        bulkFooter.add(bulkAlbumButton);
        bulkDeleteButton = addRenderableWidget(Button.builder(Component.empty(), button -> quickDelete(grid.checkedShots()))
                .bounds(bulkLeft + 3 * (bulkButton + gap), footerY, bulkButton, 20).build());
        bulkFooter.add(bulkDeleteButton);
        bulkFooter.add(addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> grid.clearChecked())
                .bounds(bulkLeft + 4 * (bulkButton + gap), footerY, bulkButton, 20).build()));

        // Bottom left, across from the count; only there while something can be brought back
        undoButton = addRenderableWidget(Button.builder(Component.empty(), button -> undoDelete())
                .bounds(margin, footerY, Math.max(40, Math.min(90, footerLeft - margin - gap)), 20).build());
        updateUndoButton();

        refreshGrid();
        updateFooter();
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
        Component empty = Component.translatable(allShots.isEmpty() ? PREFIX + "empty"
                : filter.album() != null && allShots.stream().noneMatch(shot -> shot.inAlbum(filter.album())) ? PREFIX + "albumEmpty"
                : PREFIX + "noMatch");
        grid.setShots(visibleShots, empty);
    }

    /** Moves screenshots to the trash without asking; Undo brings them back together. */
    private void quickDelete(List<Shot> shots) {
        List<Deleted> batch = new ArrayList<>();
        List<Path> gone = new ArrayList<>();
        for (Shot shot : shots) {
            try {
                Path trashed = library.moveToTrash(shot.file());
                batch.add(new Deleted(trashed, shot.name(), shot.lastModified()));
                gone.add(shot.file());
            } catch (IOException e) {
                ScreenshotToolkitClient.LOGGER.warn("Could not move {} to the trash", shot.file(), e);
            }
        }
        if (batch.isEmpty()) {
            return;
        }
        deleted.addLast(batch);
        allShots = allShots.stream().filter(other -> !gone.contains(other.file())).toList();
        grid.clearChecked();
        refreshGrid();
        updateUndoButton();
    }

    private void undoDelete() {
        List<Deleted> batch = deleted.pollLast();
        if (batch == null) {
            return;
        }
        reselect = null;
        for (Deleted last : batch) {
            try {
                Path restored = library.restoreFromTrash(last.trashed(), last.name());
                // The trash reset the file time; put it back so the screenshot keeps its date and place
                java.nio.file.Files.setLastModifiedTime(restored, java.nio.file.attribute.FileTime.fromMillis(last.lastModified()));
                if (reselect == null) {
                    reselect = new Shot(restored, 0, 0, null);
                }
            } catch (IOException e) {
                ScreenshotToolkitClient.LOGGER.warn("Could not restore {}", last.trashed(), e);
            }
        }
        updateUndoButton();
        reload();
    }

    /** Stars every checked screenshot, or unstars them all when they already are. */
    private void starChecked() {
        List<Shot> checked = grid.checkedShots();
        boolean star = !checked.stream().allMatch(Shot::favorite);
        Map<Path, Shot> updated = new HashMap<>();
        for (Shot shot : checked) {
            try {
                ShotMetadata metadata = library.setFavorite(shot.file(), star);
                updated.put(shot.file(), new Shot(shot.file(), shot.lastModified(), shot.size(), metadata));
            } catch (IOException e) {
                ScreenshotToolkitClient.LOGGER.warn("Could not save favorite for {}", shot.file(), e);
            }
        }
        allShots = allShots.stream().map(shot -> updated.getOrDefault(shot.file(), shot)).toList();
        refreshGrid();
        updateFooter();
    }

    /** Swaps the footer between its normal buttons and the bulk actions. */
    private void updateFooter() {
        if (grid == null || bulkDeleteButton == null) {
            return;
        }
        boolean checking = grid.isChecking();
        normalFooter.forEach(button -> button.visible = !checking);
        bulkFooter.forEach(button -> button.visible = checking);
        List<Shot> checked = grid.checkedShots();
        bulkDeleteButton.setMessage(Component.translatable(PREFIX + "deleteChecked", checked.size()));
        bulkStarButton.setMessage(Component.translatable(!checked.isEmpty() && checked.stream().allMatch(Shot::favorite)
                ? PREFIX + "unstarChecked" : PREFIX + "starChecked"));
        updateUndoButton();
    }

    private void updateUndoButton() {
        if (undoButton != null) {
            // The bulk actions need the room, and Undo is back as soon as they are done
            undoButton.visible = !deleted.isEmpty() && (grid == null || !grid.isChecking());
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

    public Button trashButton() {
        return trashButton;
    }

    public Button bulkDeleteButton() {
        return bulkDeleteButton;
    }

    public Button albumsButton() {
        return albumsButton;
    }

    public Button bulkAlbumButton() {
        return bulkAlbumButton;
    }

    public ShotFilter filter() {
        return filter;
    }

    public Button bulkStarButton() {
        return bulkStarButton;
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

    private void openAlbums(List<Shot> adding) {
        if (allShots == null) {
            return;
        }
        keepTextures = true;
        minecraft.gui.setScreen(new AlbumScreen(this, allShots, adding, filter.album()));
    }

    /** Called by the album screen when it closes, with the album to show (null for all screenshots). */
    void returnFromAlbums(String album, boolean changed) {
        filter = filter.withAlbum(album);
        if (changed) {
            allShots = null;
            reload();
        }
        minecraft.gui.setScreen(this);
    }

    private void openTrash() {
        keepTextures = true;
        grid.clearChecked();
        minecraft.gui.setScreen(new TrashScreen(this));
    }

    /** Called by the trash when it closes. Undo is dropped if the trash changed, since its files may be gone. */
    void returnFromTrash(boolean changed) {
        if (changed) {
            deleted.clear();
        }
        returnFrom(null, changed);
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
        boolean checking = grid != null && grid.isChecking();
        // While checking, the title says how many, like the selection bar of a photo app
        Component heading = checking ? Component.translatable(PREFIX + "checked", grid.checkedShots().size())
                : filter.album() != null ? Component.translatable(PREFIX + "inAlbum", title, filter.album()) : title;
        graphics.centeredText(font, heading, width / 2, 11, 0xFFFFFFFF);
        if (allShots != null && !checking) {
            Component count = Component.translatable(PREFIX + "count", visibleShots.size(), allShots.size());
            graphics.text(font, count, width - 12 - font.width(count), height - 12 - 15, 0xFF808080, false);
        }
    }

    /** True once the list is in and no reload is still running. */
    public boolean isLoaded() {
        return allShots != null && loading == null;
    }

    public List<Shot> visibleShots() {
        return visibleShots;
    }

    // Presses that start on the grid follow the mouse even outside it, so a selection box can scroll the grid
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
        // Escape first lets go of the checked screenshots, then closes
        if (event.key() == InputConstants.KEY_ESCAPE && grid != null && grid.isChecking()) {
            grid.clearChecked();
            return true;
        }
        return super.keyPressed(event);
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
