package com.julz.screenshottoolkit.client;

import com.julz.screenshottoolkit.core.ScreenshotLibrary;
import com.julz.screenshottoolkit.core.Shot;
import com.julz.screenshottoolkit.core.ShotMetadata;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The albums, with new, rename and delete. Opened from the gallery's Albums button, picking an
 * album shows it in the gallery. Opened with screenshots checked, a click on an album puts them
 * all in it, or takes them all out when they already are.
 */
public class AlbumScreen extends Screen {
    private static final String PREFIX = "screenshot-toolkit.albums.";
    private static final int ROW_HEIGHT = 24;
    private static final int SIDE_BUTTON = 56;

    private final GalleryScreen gallery;
    private final ScreenshotLibrary library;
    // Every screenshot, kept up to date as albums change, and the checked ones by file
    private final List<Shot> shots;
    private final Set<Path> adding;
    private String shownAlbum;
    private boolean changed;
    private int firstRow;
    private final List<Button> albumButtons = new ArrayList<>();
    private boolean noAlbums;
    private Button newButton;

    public AlbumScreen(GalleryScreen gallery, List<Shot> shots, List<Shot> adding, String shownAlbum) {
        super(adding.isEmpty() ? Component.translatable(PREFIX + "title")
                : Component.translatable(PREFIX + "addTitle", adding.size()));
        this.gallery = gallery;
        this.library = ScreenshotToolkitClient.library();
        this.shots = new ArrayList<>(shots);
        this.adding = adding.stream().map(Shot::file).collect(Collectors.toSet());
        this.shownAlbum = shownAlbum;
    }

    private boolean isAdding() {
        return !adding.isEmpty();
    }

    @Override
    protected void init() {
        albumButtons.clear();
        int margin = 12;
        int gap = 6;
        int listWidth = Math.min(width - 2 * margin, 360);
        int left = (width - listWidth) / 2;
        int top = 34;
        int footerY = height - margin - 20;
        List<String> albums = library.albums(shots);
        noAlbums = albums.isEmpty();
        List<String> rows = new ArrayList<>();
        if (!isAdding()) {
            // First row shows everything again
            rows.add(null);
        }
        rows.addAll(albums);
        int visible = Math.max(1, (footerY - 8 - top) / ROW_HEIGHT);
        firstRow = Math.clamp(firstRow, 0, Math.max(0, rows.size() - visible));
        for (int i = firstRow; i < Math.min(rows.size(), firstRow + visible); i++) {
            String album = rows.get(i);
            int y = top + (i - firstRow) * ROW_HEIGHT;
            boolean sideButtons = album != null;
            int mainWidth = sideButtons ? listWidth - 2 * (SIDE_BUTTON + gap) : listWidth;
            Button main = addRenderableWidget(Button.builder(rowLabel(album), b -> pressed(album))
                    .bounds(left, y, mainWidth, 20).build());
            albumButtons.add(main);
            if (sideButtons) {
                addRenderableWidget(Button.builder(Component.translatable(PREFIX + "rename"), b -> rename(album))
                        .bounds(left + mainWidth + gap, y, SIDE_BUTTON, 20).build());
                addRenderableWidget(Button.builder(Component.translatable(PREFIX + "delete"), b -> confirmDelete(album))
                        .bounds(left + mainWidth + gap + SIDE_BUTTON + gap, y, SIDE_BUTTON, 20).build());
            }
        }

        int footerWidth = Math.min(width - 2 * margin, 2 * 120 + gap);
        int button = (footerWidth - gap) / 2;
        int footerLeft = (width - footerWidth) / 2;
        newButton = addRenderableWidget(Button.builder(Component.translatable(PREFIX + "new"), b -> create())
                .bounds(footerLeft, footerY, button, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(footerLeft + button + gap, footerY, button, 20).build());
    }

    /** "Builds (12)", with a mark for what the checked screenshots are in while adding. */
    private Component rowLabel(String album) {
        if (album == null) {
            return Component.translatable(PREFIX + "all", shots.size());
        }
        long count = shots.stream().filter(shot -> shot.inAlbum(album)).count();
        String text = album + " (" + count + ")";
        if (isAdding()) {
            long in = shots.stream().filter(shot -> adding.contains(shot.file()) && shot.inAlbum(album)).count();
            text = (in == adding.size() ? "✔ " : in > 0 ? "- " : "") + text;
        } else if (album.equalsIgnoreCase(shownAlbum)) {
            text = "▶ " + text;
        }
        return Component.literal(text);
    }

    private void pressed(String album) {
        if (!isAdding()) {
            shownAlbum = album;
            onClose();
            return;
        }
        boolean allIn = shots.stream().filter(shot -> adding.contains(shot.file())).allMatch(shot -> shot.inAlbum(album));
        setInAlbum(album, !allIn);
        rebuildWidgets();
    }

    private void setInAlbum(String album, boolean in) {
        for (int i = 0; i < shots.size(); i++) {
            Shot shot = shots.get(i);
            if (!adding.contains(shot.file())) {
                continue;
            }
            try {
                ShotMetadata metadata = library.setInAlbum(shot.file(), album, in);
                shots.set(i, new Shot(shot.file(), shot.lastModified(), shot.size(), metadata));
                changed = true;
            } catch (IOException e) {
                ScreenshotToolkitClient.LOGGER.warn("Could not save the albums of {}", shot.file(), e);
            }
        }
    }

    private void create() {
        minecraft.gui.setScreen(new NameScreen(this, Component.translatable(PREFIX + "newTitle"),
                Component.translatable(isAdding() ? PREFIX + "newHintAdding" : PREFIX + "newHint", adding.size()),
                "", ScreenshotLibrary.MAX_ALBUM_LENGTH, typed -> {
            try {
                String name = library.createAlbum(typed, shots);
                if (isAdding()) {
                    setInAlbum(name, true);
                }
                changed = true;
                return null;
            } catch (FileAlreadyExistsException e) {
                return Component.translatable(PREFIX + "taken");
            } catch (IllegalArgumentException e) {
                return Component.translatable(PREFIX + "empty");
            } catch (IOException e) {
                ScreenshotToolkitClient.LOGGER.warn("Could not save album {}", typed, e);
                return Component.translatable(PREFIX + "failed");
            }
        }));
    }

    private void rename(String album) {
        minecraft.gui.setScreen(new NameScreen(this, Component.translatable(PREFIX + "renameTitle"),
                Component.literal(album), album, ScreenshotLibrary.MAX_ALBUM_LENGTH, typed -> {
            try {
                String name = library.renameAlbum(album, typed, shots);
                reloadShots();
                if (album.equalsIgnoreCase(shownAlbum)) {
                    shownAlbum = name;
                }
                changed = true;
                return null;
            } catch (FileAlreadyExistsException e) {
                return Component.translatable(PREFIX + "taken");
            } catch (IllegalArgumentException e) {
                return Component.translatable(PREFIX + "empty");
            } catch (IOException e) {
                ScreenshotToolkitClient.LOGGER.warn("Could not rename album {}", album, e);
                return Component.translatable(PREFIX + "failed");
            }
        }));
    }

    private void confirmDelete(String album) {
        minecraft.gui.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                try {
                    library.deleteAlbum(album, shots);
                    reloadShots();
                    if (album.equalsIgnoreCase(shownAlbum)) {
                        shownAlbum = null;
                    }
                    changed = true;
                } catch (IOException e) {
                    ScreenshotToolkitClient.LOGGER.warn("Could not delete album {}", album, e);
                }
            }
            minecraft.gui.setScreen(this);
        }, Component.translatable(PREFIX + "deleteTitle", album), Component.translatable(PREFIX + "deleteMessage")));
    }

    /** Reads the sidecars again after an album changed in many of them. */
    private void reloadShots() {
        for (int i = 0; i < shots.size(); i++) {
            Shot shot = shots.get(i);
            shots.set(i, new Shot(shot.file(), shot.lastModified(), shot.size(), library.readMetadata(shot.file())));
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int before = firstRow;
        firstRow = Math.max(0, firstRow - (int) Math.signum(scrollY));
        if (firstRow != before || scrollY < 0) {
            rebuildWidgets();
        }
        return true;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, 11, 0xFFFFFFFF);
        if (noAlbums) {
            graphics.centeredText(font, Component.translatable(PREFIX + "none"), width / 2, height / 2 - 4, 0xFFA0A0A0);
        }
    }

    public Button newButton() {
        return newButton;
    }

    /** For tests: the album rows' main buttons, in order. */
    public List<Button> albumButtons() {
        return albumButtons;
    }

    @Override
    public void onClose() {
        gallery.returnFromAlbums(shownAlbum, changed);
    }
}
