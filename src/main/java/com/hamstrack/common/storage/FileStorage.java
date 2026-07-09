package com.hamstrack.common.storage;

import java.io.InputStream;

/**
 * Backend-agnostic blob storage. Keys are server-generated, slash-separated paths
 * (never user input). Exactly one implementation is active, selected by
 * {@code app.storage.type} — see StorageProperties.
 */
public interface FileStorage {

    void store(String key, InputStream in, long contentLength, String contentType);

    /** Caller must close the returned stream. */
    InputStream open(String key);

    void delete(String key);
}
