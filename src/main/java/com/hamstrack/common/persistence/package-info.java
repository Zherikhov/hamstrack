/**
 * Per-transaction database bounds, and the one rule they share.
 *
 * <p><strong>Never on the datasource.</strong> Flyway shares it. A migration that waits for a lock,
 * or runs for minutes rewriting a table, is doing something legitimate that a request is not, and
 * neither {@code spring.datasource.hikari.connection-init-sql} nor a JDBC-URL option nor an
 * {@code ALTER ROLE} default can tell the two apart. Every bound here is applied with
 * {@code SET LOCAL}, which PostgreSQL reverts at COMMIT or ROLLBACK, so none of them can leak into
 * a pooled connection.
 *
 * <p>Beyond that the two bounds are deliberately <em>not</em> symmetrical, and the asymmetry is the
 * interesting part:
 *
 * <ul>
 *   <li>{@code LockTimeout} bounds how long a transaction <strong>waits</strong> for a row lock, and
 *       is applied by the call sites that take one. That is sound because a lock wait is
 *       <strong>structural</strong> — whether a transaction can queue is provable by reading it.</li>
 *   <li>{@code BoundedJpaTransactionManager} bounds how long any one statement may <strong>run</strong>,
 *       and is applied to <strong>every</strong> transaction the application opens. That is
 *       necessary because a slow statement is <strong>volumetric</strong> — its cost is a function
 *       of how much a tenant has accumulated, so no reading of the code produces the set of
 *       statements that will never be slow. What gets enumerated here is the complement:
 *       {@code StatementTimeout.exemptCurrentTransaction} and today's (empty) exemption set.</li>
 * </ul>
 *
 * <p>They are separate types on purpose. They answer to different settings, land on different
 * status codes — 409 with {@code Retry-After} for a lock, 422 with none for a statement — and are
 * wanted by different code, so one class holding both would carry a name narrower than its contents
 * and hand a bound to callers who never asked for it. A third bound belongs here as a third class,
 * and this paragraph is the shared half it should read first.
 */
package com.hamstrack.common.persistence;
