package com.julz.screenshottoolkit.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.DirectoryStream;
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
        Path file = metadataFile(image);
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
        Path target = uniqueName(trash, image.getFileName().toString());
        Files.move(image, target);
        // Starts the countdown now, not when the screenshot was taken
        Files.setLastModifiedTime(target, FileTime.fromMillis(System.currentTimeMillis()));
        Path sidecar = metadataFile(image);
        if (Files.exists(sidecar)) {
            Files.move(sidecar, trash.resolve(target.getFileName() + ".json"), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.deleteIfExists(thumbDirectory().resolve(image.getFileName()));
        return target;
    }

    /** Puts a trashed screenshot back. Used by the gallery's undo. */
    public Path restoreFromTrash(Path trashed, String originalName) throws IOException {
        Path target = uniqueName(directory, originalName);
        Files.move(trashed, target);
        Path sidecar = trashed.resolveSibling(trashed.getFileName() + ".json");
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
