package com.hamstrack.issue.entity;

/**
 * The role in which an issue is linked to a {@link Version} (HD-32,
 * labels-components-versions-proposal §6.1). Both roles live in ONE join table
 * ({@code issue_versions}) discriminated by this value.
 *
 * <p>Persisted as {@code VARCHAR(10)} via {@code @Enumerated(EnumType.STRING)} —
 * <strong>never</strong> a PostgreSQL {@code CREATE TYPE … AS ENUM}, which makes
 * Hibernate 7 throw JDBC cast errors on INSERT (CLAUDE.md). The database column is
 * therefore unconstrained beyond its length; this enum is the validation.
 *
 * <p>The same version may carry BOTH roles on one issue — {@code UNIQUE (issue_id,
 * version_id, link_type)} permits it deliberately, because "regression introduced
 * and fixed in 2.4.0" is a real state (§6.4).
 */
public enum VersionLinkType {

    /** "The change ships in this release" — drives the Releases page's progress bar. */
    FIX,

    /** "This defect exists in that release" — drives triage. */
    AFFECTS
}
