package com.hamstrack.search;

/**
 * The value/resolution family of a queryable HQL field (Advanced Search proposal §5).
 * Drives how the value resolver interprets a literal and how the compiler builds a
 * Criteria predicate:
 *
 * <ul>
 *   <li>{@link #ENUM_REF} — status/type/priority; a name resolves to the set of catalog
 *       ids reachable by the caller's visible projects, compiled as {@code IN (ids)};</li>
 *   <li>{@link #USER_REF} — assignee/reporter; email/displayName/{@code currentUser()}/UUID
 *       → workspace-member ids;</li>
 *   <li>{@link #ISSUE_REF} — parent; a quoted issue key {@code "DEMO-12"} → the parent id;</li>
 *   <li>{@link #LABEL_REF} — {@code label}; a name resolves to the workspace's label ids
 *       and compiles to a <em>correlated EXISTS</em> over {@code IssueLabel}, because
 *       labels are many-valued (HD-30, §3.5);</li>
 *   <li>{@link #VERSION_REF} — {@code fixVersion}/{@code affectsVersion}; a name
 *       resolves to the version ids of the caller's <em>visible projects</em> and
 *       compiles to a <em>correlated EXISTS</em> over {@code IssueVersionLink}
 *       filtered by {@code link_type}, because both roles are many-valued and share
 *       one join table (HD-32, §3.5);</li>
 *   <li>{@link #TEXT} — {@code text}; {@code ~} ILIKE over title/description;</li>
 *   <li>{@link #DATE} — a real {@code DATE} column, compared against the literal date itself;</li>
 *   <li>{@link #TIMESTAMP} — an instant-bearing column. A date literal denotes the whole
 *       inclusive UTC day, so the comparison is a {@code [day start, next day start)} window
 *       (§6.3). The column's own temporal type is read off the mapped path rather than
 *       assumed — these are not all the same Java type (see {@code HqlCompiler});</li>
 *   <li>{@link #NUMBER} — a native numeric column, compared directly (the custom-field
 *       numeric path instead compares inside a JSONB {@code EXISTS}).</li>
 * </ul>
 */
public enum FieldDataType {
    ENUM_REF,
    USER_REF,
    ISSUE_REF,
    LABEL_REF,
    VERSION_REF,
    TEXT,
    DATE,
    TIMESTAMP,
    NUMBER
}
