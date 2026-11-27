package dev.lightmap.core;

import it.unimi.dsi.fastutil.longs.LongArrayList;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** The region files of one world and dimension: {@code r.<x>.<z>.lmap} in a single directory. */
public final class RegionFiles {
    private static final String PREFIX = "r.";
    private static final String SUFFIX = ".lmap";

    private final Path dir;

    public RegionFiles(Path dir) {
        this.dir = dir;
    }

    public Path dir() {
        return dir;
    }

    /** Keys of every region that has a file. */
    public long[] list() throws IOException {
        LongArrayList keys = new LongArrayList();
        if (!Files.isDirectory(dir)) {
            return keys.toLongArray();
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, PREFIX + "*" + SUFFIX)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                String[] parts = name.substring(PREFIX.length(), name.length() - SUFFIX.length()).split("\\.");
                if (parts.length != 2) {
                    continue;
                }
                try {
                    keys.add(Regions.key(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])));
                } catch (NumberFormatException ignored) {
                    // Not one of ours.
                }
            }
        }
        return keys.toLongArray();
    }

    /** @return the file's bytes, or null if the region has no file */
    public byte[] read(long key) throws IOException {
        try {
            return Files.readAllBytes(path(key));
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    /** Writes through a temporary file so a crash or a concurrent reader never sees half a region. */
    public void write(long key, byte[] data) throws IOException {
        Files.createDirectories(dir);
        Path target = path(key);
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(temp, data);
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path path(long key) {
        return dir.resolve(PREFIX + Regions.keyX(key) + "." + Regions.keyZ(key) + SUFFIX);
    }
}
