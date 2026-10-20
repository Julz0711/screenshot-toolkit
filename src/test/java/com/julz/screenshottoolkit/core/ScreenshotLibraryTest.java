package com.julz.screenshottoolkit.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScreenshotLibraryTest {
    private static final long NOW = 1_800_000_000_000L;

    @TempDir
    Path dir;

    private Path shot(String name, long modified) throws IOException {
        Path file = dir.resolve(name);
        Files.write(file, new byte[]{1, 2, 3});
        Files.setLastModifiedTime(file, FileTime.fromMillis(modified));
        return file;
    }

    @Test
    void listsImagesNewestFirstAndSkipsHiddenFiles() throws IOException {
        shot("old.png", NOW - 10_000);
        shot("new.png", NOW);
        shot("notes.txt", NOW);
        shot(".hidden.png", NOW);
        Files.createDirectories(dir.resolve(".thumbs"));
        Files.write(dir.resolve(".thumbs/new.png"), new byte[]{1});

        ScreenshotLibrary library = new ScreenshotLibrary(dir);
        List<Shot> shots = library.list();

        assertEquals(List.of("new.png", "old.png"), shots.stream().map(Shot::name).toList());
    }

    @Test
    void missingFolderIsEmpty() throws IOException {
        assertTrue(new ScreenshotLibrary(dir.resolve("nope")).list().isEmpty());
    }

    @Test
    void recordedTimeWinsOverFileTime() throws IOException {
        Path a = shot("a.png", NOW);
        shot("b.png", NOW - 1000);
        ScreenshotLibrary library = new ScreenshotLibrary(dir);
        ShotMetadata metadata = new ShotMetadata();
        metadata.takenAt = NOW - 5000;
        library.writeMetadata(a, metadata);

        assertEquals(List.of("b.png", "a.png"), library.list().stream().map(Shot::name).toList());
    }

    @Test
    void favoritesAreStoredInTheSidecar() throws IOException {
        Path a = shot("a.png", NOW);
        ScreenshotLibrary library = new ScreenshotLibrary(dir);
        assertNull(library.readMetadata(a));

        library.setFavorite(a, true);
        assertTrue(library.list().getFirst().favorite());
        assertTrue(Files.exists(dir.resolve(".meta/a.png.json")));

        library.setFavorite(a, false);
        assertFalse(library.list().getFirst().favorite());
    }

    @Test
    void corruptSidecarIsIgnored() throws IOException {
        Path a = shot("a.png", NOW);
        Files.createDirectories(dir.resolve(".meta"));
        Files.writeString(dir.resolve(".meta/a.png.json"), "{not json");
        assertNull(new ScreenshotLibrary(dir).list().getFirst().metadata());
    }

    @Test
    void trashKeepsSidecarAndRestores() throws IOException {
        Path a = shot("a.png", NOW);
        ScreenshotLibrary library = new ScreenshotLibrary(dir);
        library.setFavorite(a, true);
        Files.createDirectories(library.thumbDirectory());
        Files.write(library.thumbDirectory().resolve("a.png"), new byte[]{1});

        Path trashed = library.moveToTrash(a);

        assertFalse(Files.exists(a));
        assertTrue(Files.exists(trashed));
        assertTrue(Files.exists(dir.resolve(".trash/a.png.json")));
        assertFalse(Files.exists(dir.resolve(".meta/a.png.json")));
        assertFalse(Files.exists(dir.resolve(".thumbs/a.png")));
        assertTrue(library.list().isEmpty());

        Path restored = library.restoreFromTrash(trashed, "a.png");
        assertEquals(a, restored);
        assertTrue(library.list().getFirst().favorite());
    }

    @Test
    void trashListsDeletesForeverAndKeepsTheDate() throws IOException {
        ScreenshotLibrary library = new ScreenshotLibrary(dir);
        Path old = shot("old.png", NOW - 50_000);
        Path trashed = library.moveToTrash(old);
        Files.setLastModifiedTime(trashed, FileTime.fromMillis(NOW - 1_000));
        Path other = library.moveToTrash(shot("other.png", NOW));
        Files.setLastModifiedTime(other, FileTime.fromMillis(NOW));

        List<Shot> trash = library.listTrash();
        assertEquals(List.of("other.png", "old.png"), trash.stream().map(Shot::name).toList());
        assertEquals(NOW - 50_000, trash.get(1).takenAt(), "the sidecar remembers when it was taken");

        // Back in the folder it sorts by its old date, not by when it was deleted
        Path restored = library.restoreFromTrash(trashed, "old.png");
        assertEquals(NOW - 50_000, library.list().stream().filter(s -> s.file().equals(restored)).findFirst().orElseThrow().takenAt());

        library.deleteForever(other);
        assertFalse(Files.exists(other));
        assertFalse(Files.exists(dir.resolve(".trash/other.png.json")));
        assertTrue(library.listTrash().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> library.deleteForever(restored));
    }

    @Test
    void albumsLiveInSidecarsAndRenameEverywhere() throws IOException {
        ScreenshotLibrary library = new ScreenshotLibrary(dir);
        Path a = shot("a.png", NOW);
        Path b = shot("b.png", NOW - 1);
        Path c = shot("c.png", NOW - 2);
        assertEquals("Builds", library.createAlbum("  Builds ", library.list()));
        assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> library.createAlbum("builds", library.list()));
        assertThrows(IllegalArgumentException.class, () -> library.createAlbum("   ", library.list()));
        library.createAlbum("Empty", library.list());

        library.setInAlbum(a, "Builds", true);
        library.setInAlbum(b, "Builds", true);
        library.setInAlbum(b, "Nether", true);
        library.setInAlbum(b, "Nether", true);
        library.moveToTrash(c);
        assertEquals(List.of("Builds", "Empty", "Nether"), library.albums(library.list()));
        assertEquals(List.of("Builds", "Nether"), library.list().get(1).albums(), "adding twice keeps one entry");
        assertEquals(2, library.list().stream().filter(s -> ShotFilter.ALL.withAlbum("builds").matches(s, NOW)).count());

        // A trashed screenshot follows the rename
        Path trashed = library.listTrash().getFirst().file();
        library.restoreFromTrash(trashed, "c.png");
        library.setInAlbum(c, "Builds", true);
        library.moveToTrash(c);

        assertEquals("Castles", library.renameAlbum("Builds", "Castles", library.list()));
        assertTrue(library.list().stream().allMatch(s -> !s.inAlbum("Builds")));
        assertEquals(2, library.list().stream().filter(s -> s.inAlbum("Castles")).count());
        assertTrue(library.listTrash().getFirst().inAlbum("Castles"));
        assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> library.renameAlbum("Castles", "nether", library.list()));
        assertEquals("CASTLES", library.renameAlbum("Castles", "CASTLES", library.list()), "a change of case is fine");

        library.deleteAlbum("Nether", library.list());
        assertEquals(List.of("CASTLES", "Empty"), library.albums(library.list()));
        assertTrue(Files.exists(b), "deleting an album keeps its screenshots");
        library.setInAlbum(a, "CASTLES", false);
        assertNull(library.readMetadata(a).albums, "an empty album list is not saved");
    }

    @Test
    void daysLeftRoundsUp() {
        long day = Duration.ofDays(1).toMillis();
        assertEquals(7, ScreenshotLibrary.daysLeft(NOW, 7, NOW));
        assertEquals(7, ScreenshotLibrary.daysLeft(NOW, 7, NOW + 1));
        assertEquals(1, ScreenshotLibrary.daysLeft(NOW, 7, NOW + 6 * day + 1));
        assertEquals(0, ScreenshotLibrary.daysLeft(NOW, 7, NOW + 8 * day));
    }

    @Test
    void trashingTheSameNameTwiceKeepsBoth() throws IOException {
        ScreenshotLibrary library = new ScreenshotLibrary(dir);
        Path first = library.moveToTrash(shot("a.png", NOW));
        Path second = library.moveToTrash(shot("a.png", NOW));
        assertNotEquals(first, second);
        assertEquals("a_1.png", second.getFileName().toString());
    }

    @Test
    void emptyTrashRemovesOnlyOldFiles() throws IOException {
        ScreenshotLibrary library = new ScreenshotLibrary(dir);
        Path old = library.moveToTrash(shot("old.png", NOW));
        Path recent = library.moveToTrash(shot("recent.png", NOW));
        library.setFavorite(dir.resolve("x.png"), true);
        Files.move(dir.resolve(".meta/x.png.json"), dir.resolve(".trash/orphan.png.json"));
        Files.setLastModifiedTime(old, FileTime.fromMillis(NOW - Duration.ofDays(8).toMillis()));
        Files.setLastModifiedTime(recent, FileTime.fromMillis(NOW - Duration.ofDays(6).toMillis()));

        int removed = library.emptyTrash(Duration.ofDays(7), NOW);

        assertEquals(1, removed);
        assertFalse(Files.exists(old));
        assertTrue(Files.exists(recent));
        assertFalse(Files.exists(dir.resolve(".trash/orphan.png.json")));
    }

    @Test
    void worldsAreSortedAndUnique() {
        assertEquals(List.of("alpha", "Beta"), ScreenshotLibrary.worlds(List.of(
                shotIn("Beta"), shotIn("alpha"), shotIn(null), shotIn("Beta"))));
    }

    @Test
    void uniqueNameCountsUp() throws IOException {
        shot("a.png", NOW);
        shot("a_1.png", NOW);
        assertEquals("a_2.png", ScreenshotLibrary.uniqueName(dir, "a.png").getFileName().toString());
        assertEquals("b.png", ScreenshotLibrary.uniqueName(dir, "b.png").getFileName().toString());
    }

    static Shot shotIn(String world) {
        ShotMetadata metadata = new ShotMetadata();
        metadata.world = world;
        return new Shot(Path.of("x.png"), NOW, 1, metadata);
    }

    @Test
    void cleansTypedNames() {
        assertEquals("my base", ScreenshotLibrary.cleanName("  my base.PNG "));
        assertEquals("a_b_c_d", ScreenshotLibrary.cleanName("a/b:c?d"));
        assertEquals("trailing", ScreenshotLibrary.cleanName("trailing... "));
        assertEquals("_hidden", ScreenshotLibrary.cleanName(".hidden"));
        assertEquals("", ScreenshotLibrary.cleanName(" .png "));
        assertEquals(ScreenshotLibrary.MAX_NAME_LENGTH, ScreenshotLibrary.cleanName("x".repeat(300)).length());
    }

    @Test
    void renameMovesSidecarAndThumbnail() throws IOException {
        Path image = shot("2026-10-20_01.03.15.png", NOW);
        ScreenshotLibrary library = new ScreenshotLibrary(dir);
        ShotMetadata metadata = new ShotMetadata();
        metadata.world = "New World";
        library.writeMetadata(image, metadata);
        Files.createDirectories(library.thumbDirectory());
        Files.write(library.thumbDirectory().resolve(image.getFileName()), new byte[]{1});

        Path renamed = library.rename(image, "Mountain base");

        assertEquals(dir.resolve("Mountain base.png"), renamed);
        assertFalse(Files.exists(image));
        assertEquals("New World", library.readMetadata(renamed).world);
        assertFalse(Files.exists(library.metadataFile(image)));
        assertTrue(Files.exists(library.thumbDirectory().resolve("Mountain base.png")));
        assertEquals(NOW, Files.getLastModifiedTime(renamed).toMillis(), "keeps its date");
    }

    @Test
    void renameRefusesTakenAndEmptyNames() throws IOException {
        Path a = shot("a.png", NOW);
        shot("b.png", NOW);
        ScreenshotLibrary library = new ScreenshotLibrary(dir);

        assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> library.rename(a, "b"));
        assertThrows(IllegalArgumentException.class, () -> library.rename(a, "  "));
        assertEquals(a, library.rename(a, "a.png"));
        assertTrue(Files.exists(a));
    }
}
