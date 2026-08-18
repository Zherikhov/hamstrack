package com.hamstrack.workspace.dto;

import com.hamstrack.common.util.DisplayText;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/workspaces/{ws}/roles/{sourceRoleId}/duplicate} — <strong>the only way
 * to create a role</strong>.
 *
 * <p><strong>There is deliberately no bare {@code POST /roles}</strong>, and that is
 * product posture rather than an oversight. The grant ceiling is a <em>subset</em> rule,
 * not a ladder, so a role composed from an empty checklist can assign nobody — not
 * Contributor, not Commenter, not even Viewer-plus-anything — and discovering that as a
 * 403 weeks later is the exact Jira complaint this epic was built against. Duplication
 * always starts from a superset. "From scratch" remains reachable by duplicating
 * <strong>Viewer</strong>, which grants nothing; it is a named, deliberate act rather than
 * the default.
 *
 * <p>{@code scope} and the permission set are copied from the source and are not on this
 * DTO. Neither is {@code key}: it is server-generated from the name, because a client-chosen
 * key could collide with a built-in's ({@code roles_scope_key_uk} is
 * {@code UNIQUE NULLS NOT DISTINCT}, so the database would allow it) and confuse every
 * operator reading {@code psql}.
 *
 * <p>Every field is a reference type, so the Jackson 3 {@code FAIL_ON_NULL_FOR_PRIMITIVES}
 * trap cannot apply — keep it that way if the record grows.
 *
 * <p><strong>No control characters in {@code name}.</strong> There is no log-injection path
 * today — role <em>keys</em> are server-generated {@code [A-Z0-9_]} and are what the audit
 * lines carry, and a name reaches the client only through Jackson-escaped
 * {@code problem+json} — but a role name is a display string with a long future ahead of it
 * (an email, a CSV export, a webhook payload), and a newline in one of those is somebody
 * else's injection. Rejected at the edge, where it is one annotation.
 *
 * <p>The pattern is {@link DisplayText#SINGLE_LINE} rather than a literal
 * {@code [^\\p{Cntrl}]*}, which is the same rule <em>as written</em> and a much weaker one
 * <em>as executed</em>: Java's {@code \p{Cntrl}} is ASCII-only, so NEL, LINE/PARAGRAPH
 * SEPARATOR and the bidi overrides — precisely the characters the paragraph above is about —
 * went straight through it (round-3 review).
 *
 * <p><strong>{@code description} takes {@link DisplayText#MULTI_LINE}, not the same pattern
 * as {@code name}.</strong> It is prose, and the editor renders it as a textarea, so
 * {@code \p{Cntrl}} (which contains {@code \n}) turned every Enter keypress into a 400 —
 * the shipped bug this split fixes. The invisible and reordering characters are still
 * refused there; only TAB/LF/CR are re-admitted. A role <em>name</em> is a label that lands
 * in single-line contexts and stays {@code SINGLE_LINE}.
 *
 * @param name defaults to {@code "<source name> copy"}. Must be unique within
 *             {@code (workspace, scope)} and must not equal a built-in's display name in
 *             that scope, case-insensitively → 409
 */
public record DuplicateRoleRequest(
        @Size(max = 80) @Pattern(regexp = DisplayText.SINGLE_LINE,
                message = "Name must not contain control characters") String name,
        @Size(max = 500) @Pattern(regexp = DisplayText.MULTI_LINE,
                message = "Description must not contain control characters") String description
) {}
