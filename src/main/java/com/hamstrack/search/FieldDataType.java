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
 *   <li>{@link #TEXT} — {@code text}; {@code ~} ILIKE over title/description;</li>
 *   <li>{@link #DATE} — a real {@code DATE} column ({@code due});</li>
 *   <li>{@link #TIMESTAMP} — created/updated; date literals use inclusive end-of-day
 *       boundaries (§6.3).</li>
 * </ul>
 */
public enum FieldDataType {
    ENUM_REF,
    USER_REF,
    ISSUE_REF,
    TEXT,
    DATE,
    TIMESTAMP,
    NUMBER
}
