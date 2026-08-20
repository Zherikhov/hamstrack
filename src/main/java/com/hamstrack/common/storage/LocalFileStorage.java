package com.hamstrack.common.storage;

import com.hamstrack.common.config.StorageProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Component
@ConditionalOnProperty(name = "app.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalFileStorage implements FileStorage {

    private final Path baseDir;

    public LocalFileStorage(StorageProperties props) {
        this.baseDir = Path.of(props.local().baseDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create attachment storage dir " + baseDir, e);
        }
    }

    @Override
    public void store(String key, InputStream in, long contentLength, String contentType) {
        var target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store " + key, e);
        }
    }

    @Override
    public InputStream open(String key) {
        try {
            return Files.newInputStream(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to open " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        var target = resolve(key);
        // Second, independent guard: only ever unlink a regular file. Even if a key
        // somehow resolved to a directory, this refuses rather than attempting to
        // remove it — defence in depth is the entire point of this class's checks,
        // because a wrongly-deleted blob has no recovery.
        if (!Files.isRegularFile(target)) {
            return;
        }
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete " + key, e);
        }
    }

    /**
     * Keys are server-generated, but this is the last line of defence for the one bug
     * class on this path with no recovery — a key set too wide destroys files.
     *
     * <p>The resolved path must be <strong>strictly below</strong> the base dir, not
     * merely not-above it: {@code ""}, {@code "."} and {@code "ws/.."} all normalize to
     * {@code baseDir} itself, which passes a bare {@code startsWith} check and would have
     * pointed {@link #delete} at the attachment root.
     */
    private Path resolve(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Storage key must not be blank");
        }
        var path = baseDir.resolve(key).normalize();
        if (!path.startsWith(baseDir) || path.equals(baseDir)) {
            throw new IllegalArgumentException("Storage key escapes base dir: " + key);
        }
        return path;
    }
}
