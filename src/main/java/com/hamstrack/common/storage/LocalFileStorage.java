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
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete " + key, e);
        }
    }

    // Keys are server-generated, but keep the escape check as defense in depth
    private Path resolve(String key) {
        var path = baseDir.resolve(key).normalize();
        if (!path.startsWith(baseDir)) {
            throw new IllegalArgumentException("Storage key escapes base dir: " + key);
        }
        return path;
    }
}
