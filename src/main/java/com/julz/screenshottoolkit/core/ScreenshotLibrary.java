package com.julz.screenshottoolkit.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * The screenshots folder: listing, metadata sidecars, favorites and the trash.
 * Has no Minecraft dependencies, so it can be unit tested.
 *
 * <pre>
 * screenshots/
 *   2026-10-19_19.30.22.png
 *   .meta/2026-10-19_19.30.22.png.json    metadata and favorite flag
 *   .thumbs/2026-10-19_19.30.22.png        small copy for the gallery
 *   .trash/2026-10-19_19.30.22.png         deleted, removed for good after a few days
 * </pre>
 */
public class ScreenshotLibrary {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path directory;

    public ScreenshotLibrary(Path directory) {
        this.directory = directory;
    }

    public Path directory() {
        return directory;
    }

    public Path metaDirectory() {
        return directory.resolve(".meta");
    }

    public Path thumbDirectory() {
        return directory.resolve(".thumbs");
    }

    public Path trashDirectory() {
        return directory.resolve(".trash");
    }

    public static boolean isImage(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return !name.startsWith(".") && (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg"));
    }

    /** Every screenshot, newest first. Reads one small sidecar per file. */
    public List<Shot> list() throws IOException {
        List<Shot> shots = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return shots;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, ScreenshotLibrary::isImage)) {
            for (Path file : stream) {
                try {
                    BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
                    if (attributes.isRegularFile()) {
                        shots.add(new Shot(file, attributes.lastModifiedTime().toMillis(), attributes.size(), readMetadata(file)));
                    }
                } catch (NoSuchFileException e) {
                    // Deleted while listing
                }
            }
        }
        shots.sort(Comparator.comparingLong(Shot::takenAt).reversed().thenComparing(Shot::name, Comparator.reverseOrder()));
        return shots;
    }

    /** The worlds and servers screenshots were taken in, for the filter button. */
    public static List<String> worlds(List<Shot> shots) {
        TreeSet<String> worlds = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Shot shot : shots) {
            if (shot.world() != null) {
                worlds.add(shot.world());
            }
        }
        return new ArrayList<>(worlds);
    }

    public Path metadataFile(Path image) {
        return metaDirectory().resolve(image.getFileName().toString() + ".json");
    }

    public ShotMetadata readMetadata(Path image) {
        Path file = metadataFile(image);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            return GSON.fromJson(reader, ShotMetadata.class);
        } catch (IOException | JsonParseException e) {
            return null;
        }
    }

    public void writeMetadata(Path image, ShotMetadata metadata) throws IOException {
        Files.createDirectories(metaDirectory());
        writeJson(metadataFile(image), metadata);
    }

    private static void writeJson(Path file, ShotMetadata metadata) throws IOException {
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temp)) {
            GSON.toJson(metadata, writer);
        }
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Stars or unstars a screenshot, creating its sidecar if it has none. Returns the new metadata. */
    public ShotMetadata setFavorite(Path image, boolean favorite) throws IOException {
        ShotMetadata metadata = readMetadata(image);
        if (metadata == null) {
            metadata = new ShotMetadata();
        }
        metadata.favorite = favorite;
        writeMetadata(image, metadata);
        return metadata;
    }

    /**
     * Moves a screenshot and its sidecar to the trash. Its thumbnail is removed, since it is
     * cheap to make again. Returns where the screenshot went.
     */
    public Path moveToTrash(Path image) throws IOException {
        Path trash = trashDirectory();
        Files.createDirectories(trash);
        // The trash uses the file time as its countdown, so the sidecar remembers when it was taken
        ShotMetadata metadata = readMetadata(image);
        if (metadata == null) {
            metadata = new ShotMetadata();
        }
        if (metadata.takenAt == null) {
            metadata.takenAt = Files.getLastModifiedTime(image).toMillis();
        }
        Path target = uniqueName(trash, image.getFileName().toString());
        Files.move(image, target);
        Files.setLastModifiedTime(target, FileTime.fromMillis(System.currentTimeMillis()));
        writeJson(trashSidecar(target), metadata);
        Files.deleteIfExists(metadataFile(image));
        Files.deleteIfExists(thumbDirectory().resolve(image.getFileName()));
        return target;
    }

    private static Path trashSidecar(Path trashed) {
        return trashed.resolveSibling(trashed.getFileName() + ".json");
    }

    /** What is in the trash, most recently deleted first. A shot's file time is when it was deleted. */
    public List<Shot> listTrash() throws IOException {
        List<Shot> shots = new ArrayList<>();
        Path trash = trashDirectory();
        if (!Files.isDirectory(trash)) {
            return shots;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(trash, ScreenshotLibrary::isImage)) {
            for (Path file : stream) {
                try {
                    BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
                    if (attributes.isRegularFile()) {
                        shots.add(new Shot(file, attributes.lastModifiedTime().toMillis(), attributes.size(), readTrashMetadata(file)));
                    }
                } catch (NoSuchFileException e) {
                    // Removed while listing
                }
            }
        }
        shots.sort(Comparator.comparingLong(Shot::lastModified).reversed().thenComparing(Shot::name));
        return shots;
    }

    private ShotMetadata readTrashMetadata(Path trashed) {
        Path file = trashSidecar(trashed);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            return GSON.fromJson(reader, ShotMetadata.class);
        } catch (IOException | JsonParseException e) {
            return null;
        }
    }

    /** Removes a trashed screenshot and its sidecar for good. */
    public void deleteForever(Path trashed) throws IOException {
        if (!trashed.getParent().equals(trashDirectory())) {
            throw new IllegalArgumentException("Not in the trash: " + trashed);
        }
        Files.deleteIfExists(trashed);
        Files.deleteIfExists(trashSidecar(trashed));
    }

    /** Whole days, rounded up, until the trash removes a screenshot deleted at {@code trashedAt}. */
    public static int daysLeft(long trashedAt, int trashDays, long now) {
        long left = trashedAt + Duration.ofDays(trashDays).toMillis() - now;
        return (int) Math.max(0, (left + Duration.ofDays(1).toMillis() - 1) / Duration.ofDays(1).toMillis());
    }

    /** Puts a trashed screenshot back. Used by the gallery's undo. */
    public Path restoreFromTrash(Path trashed, String originalName) throws IOException {
        Path target = uniqueName(directory, originalName);
        Files.move(trashed, target);
        Path sidecar = trashSidecar(trashed);
        if (Files.exists(sidecar)) {
            Files.createDirectories(metaDirectory());
            Files.move(sidecar, metadataFile(target), StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    /** Removes trashed files older than the given age. Returns how many screenshots were removed. */
    public int emptyTrash(Duration maxAge, long now) throws IOException {
        Path trash = trashDirectory();
        if (!Files.isDirectory(trash)) {
            return 0;
        }
        int removed = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(trash)) {
            for (Path file : stream) {
                if (file.getFileName().toString().endsWith(".json")) {
                    continue;
                }
                if (now - Files.getLastModifiedTime(file).toMillis() > maxAge.toMillis()) {
                    Files.deleteIfExists(file);
                    Files.deleteIfExists(file.resolveSibling(file.getFileName() + ".json"));
                    removed++;
                }
            }
        }
        // Sidecars whose image was removed by hand
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(trash, "*.json")) {
            for (Path sidecar : stream) {
                String name = sidecar.getFileName().toString();
                if (!Files.exists(sidecar.resolveSibling(name.substring(0, name.length() - ".json".length())))) {
                    Files.deleteIfExists(sidecar);
                }
            }
        }
        return removed;
    }

    // ---- Albums ----
    // A screenshot's albums live in its sidecar, so the image files never move. .meta/albums.json
    // keeps the album names too, so an album exists before anything is in it and keeps its order.

    /** Longest album name. */
    public static final int MAX_ALBUM_LENGTH = 40;

    private Path albumsFile() {
        return metaDirectory().resolve("albums.json");
    }

    private List<String> readAlbumRegistry() {
        Path file = albumsFile();
        if (!Files.isRegularFile(file)) {
            return new ArrayList<>();
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            String[] names = GSON.fromJson(reader, String[].class);
            return names == null ? new ArrayList<>() : new ArrayList<>(List.of(names));
        } catch (IOException | JsonParseException e) {
            return new ArrayList<>();
        }
    }

    private void writeAlbumRegistry(List<String> names) throws IOException {
        Files.createDirectories(metaDirectory());
        Path file = albumsFile();
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temp)) {
            GSON.toJson(names, writer);
        }
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Every album, sorted by name: the saved ones and any a sidecar mentions. */
    public List<String> albums(List<Shot> shots) {
        TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        names.addAll(readAlbumRegistry());
        for (Shot shot : shots) {
            names.addAll(shot.albums());
        }
        return new ArrayList<>(names);
    }

    /** Trims a typed album name and limits its length; "" when nothing is left. */
    public static String cleanAlbumName(String typed) {
        String name = typed.strip().replaceAll("\\p{Cntrl}", "");
        return name.length() > MAX_ALBUM_LENGTH ? name.substring(0, MAX_ALBUM_LENGTH).strip() : name;
    }

    /**
     * Saves a new, empty album and returns its cleaned name. Throws IllegalArgumentException when
     * the name is empty and FileAlreadyExistsException when an album has it already, in any case.
     */
    public String createAlbum(String typed, List<Shot> shots) throws IOException {
        String name = cleanAlbumName(typed);
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Empty album name");
        }
        if (albums(shots).stream().anyMatch(existing -> existing.equalsIgnoreCase(name))) {
            throw new FileAlreadyExistsException(name);
        }
        List<String> registry = readAlbumRegistry();
        registry.add(name);
        writeAlbumRegistry(registry);
        return name;
    }

    /** Adds a screenshot to an album or takes it out. Returns the new metadata. */
    public ShotMetadata setInAlbum(Path image, String album, boolean in) throws IOException {
        ShotMetadata metadata = readMetadata(image);
        if (metadata == null) {
            metadata = new ShotMetadata();
        }
        List<String> albums = metadata.albums == null ? new ArrayList<>() : new ArrayList<>(metadata.albums);
        albums.removeIf(name -> name.equalsIgnoreCase(album));
        if (in) {
            albums.add(album);
        }
        metadata.albums = albums.isEmpty() ? null : albums;
        writeMetadata(image, metadata);
        return metadata;
    }

    /**
     * Renames an album everywhere, including screenshots in the trash. Returns the cleaned name.
     * Throws like {@link #createAlbum} when the new name is empty or taken by another album.
     */
    public String renameAlbum(String album, String typed, List<Shot> shots) throws IOException {
        String name = cleanAlbumName(typed);
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Empty album name");
        }
        if (!name.equalsIgnoreCase(album) && albums(shots).stream().anyMatch(existing -> existing.equalsIgnoreCase(name))) {
            throw new FileAlreadyExistsException(name);
        }
        replaceAlbum(album, name, shots);
        return name;
    }

    /** Removes an album. Its screenshots stay; they are only no longer in it. */
    public void deleteAlbum(String album, List<Shot> shots) throws IOException {
        replaceAlbum(album, null, shots);
    }

    private void replaceAlbum(String album, String replacement, List<Shot> shots) throws IOException {
        List<String> registry = readAlbumRegistry();
        int at = -1;
        for (int i = 0; i < registry.size(); i++) {
            if (registry.get(i).equalsIgnoreCase(album)) {
                at = i;
            }
        }
        registry.removeIf(name -> name.equalsIgnoreCase(album));
        if (replacement != null) {
            registry.add(at >= 0 ? Math.min(at, registry.size()) : registry.size(), replacement);
        }
        writeAlbumRegistry(registry);
        for (Shot shot : shots) {
            if (shot.inAlbum(album)) {
                setInAlbum(shot.file(), album, false);
                if (replacement != null) {
                    setInAlbum(shot.file(), replacement, true);
                }
            }
        }
        // Trashed screenshots come back in the album under its new name, or in none
        for (Shot shot : listTrash()) {
            if (shot.inAlbum(album)) {
                ShotMetadata metadata = shot.metadata();
                metadata.albums.removeIf(name -> name.equalsIgnoreCase(album));
                if (replacement != null) {
                    metadata.albums.add(replacement);
                }
                if (metadata.albums.isEmpty()) {
                    metadata.albums = null;
                }
                writeJson(trashSidecar(shot.file()), metadata);
            }
        }
    }

    /** Longest name, without the extension, that rename accepts. */
    public static final int MAX_NAME_LENGTH = 100;

    /**
     * Turns what a player typed into a safe file name without extension: characters Windows,
     * macOS or Linux reject become underscores, a typed ".png" is dropped, and trailing dots and
     * spaces (which Windows strips) are removed. Returns "" when nothing usable is left.
     */
    public static String cleanName(String typed) {
        String name = typed.strip();
        if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".png")) {
            name = name.substring(0, name.length() - 4);
        }
        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        name = name.replaceAll("[. ]+$", "");
        if (name.startsWith(".")) {
            // Hidden files are not listed
            name = "_" + name.substring(1);
        }
        return name.length() > MAX_NAME_LENGTH ? name.substring(0, MAX_NAME_LENGTH) : name;
    }

    /**
     * Renames a screenshot, keeping its extension, and moves its sidecar and thumbnail along.
     * Returns the new file. Throws FileAlreadyExistsException when another screenshot has the
     * name, and IllegalArgumentException when the name has nothing usable in it.
     */
    public Path rename(Path image, String typed) throws IOException {
        String base = cleanName(typed);
        if (base.isEmpty()) {
            throw new IllegalArgumentException("Empty name");
        }
        String name = image.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String extension = dot > 0 ? name.substring(dot) : "";
        Path target = image.resolveSibling(base + extension);
        if (target.equals(image)) {
            return image;
        }
        // A change of case only is fine even where file names ignore case
        boolean sameName = target.getFileName().toString().equalsIgnoreCase(name);
        if (!sameName && Files.exists(target)) {
            throw new FileAlreadyExistsException(target.toString());
        }
        Files.move(image, target);
        Path sidecar = metadataFile(image);
        if (Files.exists(sidecar)) {
            Files.move(sidecar, metadataFile(target), StandardCopyOption.REPLACE_EXISTING);
        }
        Path thumbnail = thumbDirectory().resolve(name);
        if (Files.exists(thumbnail)) {
            Files.move(thumbnail, thumbDirectory().resolve(target.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    /** A file name in the folder that is not taken yet: name.png, name_1.png, name_2.png and so on. */
    public static Path uniqueName(Path folder, String name) {
        Path candidate = folder.resolve(name);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; Files.exists(candidate); i++) {
            candidate = folder.resolve(base + "_" + i + extension);
        }
        return candidate;
    }

    /** Like {@link #list()}, for callers that cannot handle checked exceptions. */
    public List<Shot> listUnchecked() {
        try {
            return list();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
