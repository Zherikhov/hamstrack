package com.hamstrack.issue;

import com.hamstrack.issue.repository.VersionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>The regression this class exists for is invisible without it.</strong>
 *
 * <p>{@code versions.released} / {@code released_at} are maintained ONLY by the
 * conditional bulk UPDATEs {@code VersionRepository.markReleased} /
 * {@code markUnreleased}, which are the concurrency arbiter of the release lifecycle.
 * While those two columns were entity-<em>writable</em>, any ordinary
 * {@code saveAndFlush} on a {@code Version} that had been loaded EARLIER in the same
 * request — a PATCH of the description, an archive/unarchive — emitted a full-column
 * UPDATE from its pre-race snapshot and silently wrote {@code released = false,
 * released_at = NULL} back over a release a concurrent curator had just made.
 *
 * <p>Why nothing else catches it:
 * <ul>
 *   <li>{@code versions_released_ck} cannot: the stale pair
 *       {@code (false, NULL)} is internally <em>consistent</em>, so the CHECK stays
 *       silent;</li>
 *   <li>versions have no history rows, so the corruption is unattributable after the
 *       fact — the release simply "didn't happen", with nobody to blame;</li>
 *   <li>every single-threaded test passes, because the clobber needs two transactions
 *       overlapping on one row.</li>
 * </ul>
 *
 * <p>The fix is {@code @Column(updatable = false)} on both fields (the documented
 * {@code projects.issue_seq} pattern: a DB-maintained counter written by native SQL
 * must be non-updatable on the entity, or a stale managed copy will clobber it).
 * <strong>Negative control:</strong> removing {@code updatable = false} from
 * {@link com.hamstrack.issue.entity.Version#released} /
 * {@code releasedAt} makes {@link #aStaleSnapshotFlushedAfterAConcurrentReleaseCannotRevertIt}
 * fail — that is how this test was verified to be load-bearing rather than
 * tautological. If you ever "simplify" those annotations away, run this test.
 *
 * <p>Assertions read the row with <strong>raw SQL</strong> on purpose: a JPA read
 * could be served from the very persistence context whose behaviour is under test.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class VersionLifecycleIntegrityTest extends VersionTestBase {

    @Autowired VersionRepository versionRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;

    // ==================================================== the concurrency harness

    /**
     * The exact shape of the bug: an outer transaction loads the version (its
     * pre-race snapshot), a concurrent transaction releases it, and the outer one then
     * flushes an unrelated edit. The release must survive AND the edit must land.
     */
    @Test
    void aStaleSnapshotFlushedAfterAConcurrentReleaseCannotRevertIt() throws Exception {
        var ctx = newProject();
        var versionId = createPlannedVersion(ctx, "2.4.0", "2026-09-01");
        var projectId = ctx.projectId();

        outer().executeWithoutResult(status -> {
            // (1) the PRE-RACE SNAPSHOT — loaded while the version is still unreleased
            var snapshot = versionRepository.findById(versionId).orElseThrow();
            assert !snapshot.isReleased() : "precondition: the snapshot is taken unreleased";

            // (2) a CONCURRENT curator releases it, in its own committed transaction.
            //     REQUIRES_NEW suspends the outer persistence context, so the snapshot
            //     above stays exactly as stale as it would be across two HTTP requests.
            int affected = requiresNew().execute(inner -> versionRepository.markReleased(
                    versionId, projectId,
                    OffsetDateTime.now(ZoneOffset.UTC), LocalDate.of(2026, 9, 1)));
            assert affected == 1 : "the concurrent release must have won its conditional UPDATE";

            // (3) the outer request finishes its own, unrelated work and flushes.
            snapshot.setDescription("edited by the stale snapshot");
            versionRepository.saveAndFlush(snapshot);
        });

        var row = rawVersion(versionId);
        // THE assertion: the release survived a full-column UPDATE from a stale entity.
        assert Boolean.TRUE.equals(row.get("released"))
                : "a stale snapshot reverted the release — released/released_at must be "
                + "@Column(updatable = false); got " + row;
        assert row.get("released_at") != null
                : "released_at was nulled out by the stale flush; got " + row;
        // …and the legitimate part of the stale write DID land, so the guard is
        // surgical rather than "the entity is read-only now".
        assert "edited by the stale snapshot".equals(row.get("description")) : row;

        // The API agrees with the row — nothing is being papered over in the mapper.
        getVersion(ctx, ctx.token(), versionId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.released").value(true))
                .andExpect(jsonPath("$.description").value("edited by the stale snapshot"));
    }

    /**
     * The same race with {@code archive} as the losing writer rather than a PATCH —
     * {@code VersionService.setArchived} is the other code path that mutates a
     * previously-loaded {@code Version} and calls {@code saveAndFlush}.
     */
    @Test
    void aStaleSnapshotArchivedAfterAConcurrentReleaseCannotRevertItEither() throws Exception {
        var ctx = newProject();
        var versionId = createPlannedVersion(ctx, "2.4.0", "2026-09-01");
        var projectId = ctx.projectId();

        outer().executeWithoutResult(status -> {
            var snapshot = versionRepository.findById(versionId).orElseThrow();
            requiresNew().execute(inner -> versionRepository.markReleased(
                    versionId, projectId,
                    OffsetDateTime.now(ZoneOffset.UTC), LocalDate.of(2026, 9, 1)));
            snapshot.setArchivedAt(java.time.Instant.now());
            versionRepository.saveAndFlush(snapshot);
        });

        var row = rawVersion(versionId);
        assert Boolean.TRUE.equals(row.get("released")) : row;
        assert row.get("released_at") != null : row;
        assert row.get("archived_at") != null : "the archive itself must still have applied; " + row;
    }

    /** And the mirror image: a stale snapshot must not resurrect an un-release either. */
    @Test
    void aStaleSnapshotFlushedAfterAConcurrentUnreleaseCannotReReleaseIt() throws Exception {
        var ctx = newProject();
        var versionId = createPlannedVersion(ctx, "2.4.0", "2026-09-01");
        var projectId = ctx.projectId();
        releaseVersion(ctx, ctx.token(), versionId).andExpect(status().isOk());

        outer().executeWithoutResult(status -> {
            // snapshot taken while RELEASED
            var snapshot = versionRepository.findById(versionId).orElseThrow();
            assert snapshot.isReleased();
            requiresNew().execute(inner -> versionRepository.markUnreleased(versionId, projectId));
            snapshot.setDescription("edited after the un-release");
            versionRepository.saveAndFlush(snapshot);
        });

        var row = rawVersion(versionId);
        assert Boolean.FALSE.equals(row.get("released"))
                : "a stale snapshot re-released a version that had just been un-released; " + row;
        assert row.get("released_at") == null : row;
        assert "edited after the un-release".equals(row.get("description")) : row;
    }

    // ==================================================== the single-threaded floor

    /** {@code create} must still INSERT the pair — {@code updatable=false} is UPDATE-only. */
    @Test
    void createStillInsertsUnreleasedWithNoReleasedAt() throws Exception {
        var ctx = newProject();
        var id = createVersion(ctx, "2.4.0");

        var row = rawVersion(id);
        assert Boolean.FALSE.equals(row.get("released"))
                : "updatable=false must not have broken the INSERT of released; " + row;
        assert row.get("released_at") == null : row;
        assert row.get("archived_at") == null : row;
    }

    /**
     * The end-to-end sequence a curator actually performs. Each of these calls loads
     * the version, mutates something else and flushes — exactly the shape that used to
     * revert the release, just serialized instead of interleaved.
     */
    @Test
    void releaseThenPatchThenArchiveLeavesTheVersionReleased() throws Exception {
        var ctx = newProject();
        var id = createPlannedVersion(ctx, "2.4.0", "2026-09-01");

        releaseVersion(ctx, ctx.token(), id).andExpect(status().isOk());
        patchVersion(ctx, ctx.token(), id, "{\"description\":\"shipped on time\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.released").value(true));
        patchVersion(ctx, ctx.token(), id, "{\"name\":\"2.4.0 final\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.released").value(true));
        archiveVersion(ctx, ctx.token(), id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.released").value(true))
                .andExpect(jsonPath("$.archived").value(true));
        unarchiveVersion(ctx, ctx.token(), id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.released").value(true));

        var row = rawVersion(id);
        assert Boolean.TRUE.equals(row.get("released")) : row;
        assert row.get("released_at") != null : row;
        assert "2.4.0 final".equals(row.get("name")) : row;
        assert "shipped on time".equals(row.get("description")) : row;
    }

    /** Un-release flips the pair at the ROW level, and a second one is a 409. */
    @Test
    void unreleaseClearsThePairInTheRowAndASecondUnreleaseIs409() throws Exception {
        var ctx = newProject();
        var id = createPlannedVersion(ctx, "2.4.0", "2026-09-01");

        releaseVersion(ctx, ctx.token(), id).andExpect(status().isOk());
        var released = rawVersion(id);
        assert Boolean.TRUE.equals(released.get("released")) && released.get("released_at") != null
                : released;

        unreleaseVersion(ctx, ctx.token(), id).andExpect(status().isOk());
        var unreleased = rawVersion(id);
        assert Boolean.FALSE.equals(unreleased.get("released")) : unreleased;
        assert unreleased.get("released_at") == null : unreleased;
        // release_date is preserved — it is the plan, and losing it would make the
        // round trip lossy (the story's reversibility criterion).
        assert unreleased.get("release_date") != null : unreleased;

        unreleaseVersion(ctx, ctx.token(), id).andExpect(status().isConflict());
        // The refused second call changed nothing at the row level either.
        assert Boolean.FALSE.equals(rawVersion(id).get("released"));
    }

    /**
     * {@code versions_released_ck} is the last line of defence, and the only way to
     * reach it is raw SQL — every Java path now goes through the two conditional
     * UPDATEs. Asserted so the CHECK cannot be dropped by a future migration without
     * something failing.
     */
    @Test
    void theDatabaseRefusesAnInconsistentReleasedPair() throws Exception {
        var ctx = newProject();
        var id = createVersion(ctx, "2.4.0");

        // released = true with a NULL released_at
        assertRawUpdateRejected(
                "UPDATE versions SET released = true WHERE id = ?", id);
        // …and released = false with a released_at still set
        assertRawUpdateRejected(
                "UPDATE versions SET released = false, released_at = NOW() WHERE id = ?", id);
    }

    // ==================================================== helpers

    /** A plain committed transaction — the "outer request". */
    private TransactionTemplate outer() {
        return new TransactionTemplate(transactionManager);
    }

    /**
     * A transaction that SUSPENDS the caller's persistence context, so the entity the
     * caller already loaded stays stale exactly as it would across two HTTP requests.
     * That suspension is the whole point of the harness — with plain {@code REQUIRED}
     * the bulk UPDATE would run in the same context and the race would not exist.
     */
    private TransactionTemplate requiresNew() {
        var template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    /** The version row as the DATABASE holds it — never through JPA. */
    private Map<String, Object> rawVersion(UUID id) {
        return jdbcTemplate.queryForMap(
                "SELECT name, description, release_date, released, released_at, archived_at "
                        + "FROM versions WHERE id = ?", id);
    }

    private void assertRawUpdateRejected(String sql, UUID id) {
        try {
            jdbcTemplate.update(sql, id);
            throw new AssertionError("versions_released_ck did not reject: " + sql);
        } catch (org.springframework.dao.DataIntegrityViolationException expected) {
            assert expected.getMessage() != null
                    && expected.getMessage().contains("versions_released_ck")
                    : "rejected, but not by versions_released_ck: " + expected.getMessage();
        }
    }
}
