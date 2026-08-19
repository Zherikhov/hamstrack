package com.hamstrack.common.storage;

import com.hamstrack.common.config.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HD-137 review round 5 — {@code LocalFileStorage.resolve} is the last line of defence for
 * the one bug class on the attachment path that has <strong>no recovery</strong>: a key set
 * too wide does not leak data, it destroys files.
 *
 * <p>The guard originally rejected only paths <em>above</em> the base dir, never equality
 * <em>with</em> it. {@code ""}, {@code "."} and {@code "ws/.."} all normalize back to
 * {@code baseDir}, which passes a bare {@code startsWith} check — so {@code delete} would
 * have called {@code Files.deleteIfExists(baseDir)}, removing the attachment root whenever
 * it happened to be empty and otherwise throwing {@code DirectoryNotEmptyException} into
 * {@code AttachmentService}'s after-commit warn handler, which swallows it. No current call
 * site can produce such a key (they are all server-generated
 * {@code ws/{wsId}/issues/{issueId}/{uuid}}), which is precisely why this needs a test: a
 * guard whose entire value is that it fails, and which nothing exercises, is decoration.
 */
class LocalFileStorageResolveTest {

    @TempDir
    Path baseDir;

    private LocalFileStorage storage() {
        return new LocalFileStorage(new StorageProperties("local",
                new StorageProperties.Local(baseDir.toString()), null));
    }

    @ParameterizedTest(name = "key \"{0}\" is refused")
    @ValueSource(strings = {"", "   ", ".", "./", "ws/..", "ws/1/../..", "..", "../outside"})
    void aKeyThatDoesNotLandStrictlyBelowTheBaseDirIsRefused(String key) {
        var storage = storage();
        assertThatThrownBy(() -> storage.delete(key)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.open(key)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.store(key, new ByteArrayInputStream(new byte[]{1}), 1, "text/plain"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusingTheEmptyKeyLeavesTheAttachmentRootItselfIntact() {
        var storage = storage();

        assertThatThrownBy(() -> storage.delete("")).isInstanceOf(IllegalArgumentException.class);

        // The failure mode this guard exists for: an empty base dir is deletable, so a
        // key normalizing to it would have silently unlinked the storage root.
        assertThat(Files.isDirectory(baseDir)).isTrue();
    }

    @Test
    void deleteIsANoOpForAPathThatIsNotARegularFile() throws Exception {
        var storage = storage();
        Files.createDirectories(baseDir.resolve("ws/1/issues"));

        // Independent of the key check: even a well-formed, strictly-below key that
        // happens to name a directory must not be unlinked.
        storage.delete("ws/1/issues");

        assertThat(Files.isDirectory(baseDir.resolve("ws/1/issues"))).isTrue();
    }

    @Test
    void aServerGeneratedKeyStillStoresOpensAndDeletes() throws Exception {
        var storage = storage();
        var key = "ws/11111111-1111-1111-1111-111111111111/issues/22222222-2222-2222-2222-222222222222/blob";

        storage.store(key, new ByteArrayInputStream("hamstrack".getBytes(StandardCharsets.UTF_8)), 9, "text/plain");
        try (var in = storage.open(key)) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hamstrack");
        }

        storage.delete(key);
        assertThat(Files.exists(baseDir.resolve(key))).isFalse();
        // Deleting a key that is already gone stays a no-op, as the after-commit
        // cleanup in AttachmentService relies on.
        storage.delete(key);
    }
}
