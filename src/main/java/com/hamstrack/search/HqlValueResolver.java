package com.hamstrack.search;

import com.hamstrack.common.util.Points;
import com.hamstrack.issue.entity.Priority;
import com.hamstrack.search.parser.ast.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Resolves a parser {@link Value} against a target {@link FieldDescriptor} and the
 * per-request {@link ResolutionContext} into a bindable {@link ResolvedValue}
 * (Advanced Search proposal §6). A resolution failure is a
 * {@link HqlSemanticException} (422, field-anchored). All time evaluation is
 * <strong>server UTC</strong> (§6.3, §16.2).
 *
 * <p>Never touches SQL — it only maps names/functions/dates to ids and instants
 * that the compiler binds as Criteria parameters.
 */
@Component
public class HqlValueResolver {

    /** Resolve one value for {@code =/!=/IN} membership (id-set / date / text). */
    public ResolvedValue resolve(FieldDescriptor field, Value value, ResolutionContext ctx) {
        return switch (field.dataType()) {
            case ENUM_REF -> resolveEnum(field, value, ctx);
            case USER_REF -> resolveUser(field, value, ctx);
            case LABEL_REF -> resolveLabel(field, value, ctx);
            case VERSION_REF -> resolveVersion(field, value, ctx);
            // ISSUE_REF (parent) needs an issue lookup — the compiler routes it to
            // HqlParentResolver, so it never reaches this resolver.
            case ISSUE_REF -> throw new IllegalStateException(
                    "parent resolution is handled by HqlParentResolver");
            case DATE, TIMESTAMP -> resolveDate(field, value, ctx);
            case TEXT -> new ResolvedValue.TextTerm(requireString(field, value));
            // HD-22 §4.7: NUMBER went live with the native `storyPoints` column, replacing
            // this branch's former "not queryable in MVP" throw.
            case NUMBER -> resolveNumber(field, value);
        };
    }

    /**
     * Resolve a {@code priority} operand for an ORDERED comparison ({@code >/</>=/<=})
     * to its catalog {@code position} (§5.3). Only {@code priority} reaches here.
     */
    public ResolvedValue.PositionValue resolvePriorityPosition(FieldDescriptor field, Value value,
                                                               ResolutionContext ctx) {
        String name = requireName(field, value);
        var matches = ctx.prioritiesByName().get(SearchNames.key(name));
        if (matches == null || matches.isEmpty()) {
            throw new HqlSemanticException(
                    "No priority named '" + name + "' in this workspace", field.name());
        }
        // A name maps to one catalog row today; if several, ordered comparison needs
        // one rank — use the first (positions are a flat catalog column).
        Priority p = matches.getFirst();
        return new ResolvedValue.PositionValue(p.getPosition());
    }

    // ---- ENUM_REF (status / type / priority by name → id set) ----

    private ResolvedValue resolveEnum(FieldDescriptor field, Value value, ResolutionContext ctx) {
        String name = requireName(field, value);
        var byName = switch (field.name()) {
            case "status" -> ctx.statusIdsByName();
            case "type" -> ctx.typeIdsByName();
            case "priority" -> ctx.priorityIdsByName();
            // component (HD-31): built from the VISIBLE PROJECT set, so a name never
            // resolves through a project the actor cannot see — and a name owned by two
            // visible projects maps to BOTH ids, matching issues in either.
            case "component" -> ctx.componentIdsByName();
            // sprint (HD-22): built from the VISIBLE PROJECT set for the same reason as
            // component — a name must never resolve through a project the actor cannot
            // see — and a name run by two visible projects maps to BOTH ids.
            case "sprint" -> ctx.sprintIdsByName();
            default -> throw new HqlSemanticException(
                    "Field '" + field.name() + "' is not yet queryable", field.name());
        };
        var ids = byName.get(SearchNames.key(name));
        if (ids == null || ids.isEmpty()) {
            throw new HqlSemanticException(
                    "No " + field.name() + " named '" + name + "' in this workspace", field.name());
        }
        return new ResolvedValue.Ids(ids);
    }

    // ---- LABEL_REF (label name → the workspace's label ids) ----

    /**
     * Resolve a {@code label} operand (HD-30, §3.5). Labels are workspace-scoped, so
     * the lookup map IS the tenant boundary — a name from another workspace simply
     * isn't there and yields the standard 422. Archived labels are excluded from name
     * resolution; a deleted label named in a saved filter therefore surfaces as
     * "No label named '…' in this workspace" at RUN time, which is the documented,
     * intended behavior (save-time validation is structural only).
     */
    private ResolvedValue resolveLabel(FieldDescriptor field, Value value, ResolutionContext ctx) {
        String name = requireName(field, value);
        var ids = ctx.labelIdsByName().get(SearchNames.key(name));
        if (ids == null || ids.isEmpty()) {
            throw new HqlSemanticException(
                    "No label named '" + name + "' in this workspace", field.name());
        }
        return new ResolvedValue.Ids(ids);
    }

    // ---- VERSION_REF (version name → the visible projects' version ids) ----

    /**
     * Resolve a {@code fixVersion} / {@code affectsVersion} operand (HD-32, §3.5).
     * Versions are project-scoped, so the lookup map is built from the caller's
     * <em>visible projects</em> only — a name never resolves through a project the
     * search scope would hide, and a name shipped by two visible projects maps to BOTH
     * ids so issues in either match.
     *
     * <p>ONE map serves both roles: the fix/affects distinction is applied by the
     * compiler's {@code link_type} filter, not here — a version can legitimately be
     * named in either role.
     *
     * <p>Archived versions are excluded from name resolution, so a deleted or archived
     * version named in a saved filter surfaces as "No version named '…' in this
     * workspace" at RUN time — the documented, intended behavior (save-time validation
     * is structural only).
     */
    private ResolvedValue resolveVersion(FieldDescriptor field, Value value, ResolutionContext ctx) {
        String name = requireName(field, value);
        var ids = ctx.versionIdsByName().get(SearchNames.key(name));
        if (ids == null || ids.isEmpty()) {
            throw new HqlSemanticException(
                    "No version named '" + name + "' in this workspace", field.name());
        }
        return new ResolvedValue.Ids(ids);
    }

    // ---- USER_REF (email / displayName / currentUser() / UUID → member ids) ----

    private ResolvedValue resolveUser(FieldDescriptor field, Value value, ResolutionContext ctx) {
        if (value instanceof Value.FunctionCall fn) {
            if (fn.name().equalsIgnoreCase("currentUser")) {
                return new ResolvedValue.Ids(List.of(ctx.actor().getId()));
            }
            throw new HqlSemanticException(
                    "Function '" + fn.name() + "()' is not valid for field '" + field.name() + "'", field.name());
        }
        String raw = requireString(field, value);

        // 1) exact email match (case-insensitive), scoped to workspace members
        var byEmail = ctx.members().stream()
                .filter(m -> m.email() != null && m.email().equalsIgnoreCase(raw))
                .map(ResolutionContext.Member::id)
                .toList();
        if (!byEmail.isEmpty()) {
            return new ResolvedValue.Ids(byEmail);
        }
        // 2) exact displayName match (case-insensitive) → IN over all matches (§6.2)
        var byName = ctx.members().stream()
                .filter(m -> m.displayName() != null && m.displayName().equalsIgnoreCase(raw))
                .map(ResolutionContext.Member::id)
                .toList();
        if (!byName.isEmpty()) {
            return new ResolvedValue.Ids(byName);
        }
        // 3) raw UUID fallback — but only if it belongs to a workspace member (§16.1)
        var asUuid = tryParseUuid(raw);
        if (asUuid != null) {
            boolean isMember = ctx.members().stream().anyMatch(m -> m.id().equals(asUuid));
            if (isMember) {
                return new ResolvedValue.Ids(List.of(asUuid));
            }
        }
        throw new HqlSemanticException(
                "No member matching '" + raw + "' in this workspace", field.name());
    }

    // ---- NUMBER (native numeric column — storyPoints, HD-22 §4.7) ----

    /**
     * Resolve a numeric operand for a native {@code NUMBER} field. Accepts a bare
     * numeric literal ({@code storyPoints >= 5}) and, for convenience, a quoted one —
     * both are parsed with {@link java.math.BigDecimal} so no precision is lost between
     * the lexer's raw text and the {@code NUMERIC(5,2)} column.
     *
     * <p>A function call or an unparseable literal is a {@link HqlSemanticException}
     * (422, field-anchored), never a silent zero.
     *
     * <p><strong>The operand is bounded to the field's own domain</strong> (security
     * review L3). Parsing alone accepts {@code storyPoints > 1e999999999}, which
     * PostgreSQL then rejects during parameter conversion (SQLSTATE 22003) — there is no
     * generic handler for that, so a plain member gets a bare 500 plus an ERROR stack
     * trace out of a filter that could never have matched a row. The bounds come from
     * {@link Points}, the same constants {@code IssueService.requireValidStoryPoints}
     * enforces on the write path, so the query and write domains cannot drift.
     * {@code NUMBER}'s only member today is {@code storyPoints}; a future one must bring
     * its own domain here rather than widen this check.
     */
    private ResolvedValue resolveNumber(FieldDescriptor field, Value value) {
        String raw = switch (value) {
            case Value.NumberLiteral n -> n.raw();
            case Value.StringLiteral s -> s.value();
            case Value.FunctionCall fn -> throw new HqlSemanticException(
                    "Function '" + fn.name() + "()' is not valid for field '" + field.name() + "'",
                    field.name());
        };
        java.math.BigDecimal parsed;
        try {
            parsed = new java.math.BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw new HqlSemanticException(
                    "Field '" + field.name() + "' expects a number", field.name());
        }
        if (!Points.inStoryPointRange(parsed)) {
            throw new HqlSemanticException(
                    "Field '" + field.name() + "' accepts values between 0 and "
                            + Points.MAX_STORY_POINTS.toPlainString(), field.name());
        }
        if (!Points.hasStoryPointScale(parsed)) {
            throw new HqlSemanticException(
                    "Field '" + field.name() + "' allows at most "
                            + Points.MAX_STORY_POINT_SCALE + " decimal places", field.name());
        }
        return new ResolvedValue.NumberValue(parsed);
    }

    // ---- DATE / TIMESTAMP (literal / now() / startOfWeek(), server UTC) ----

    private ResolvedValue resolveDate(FieldDescriptor field, Value value, ResolutionContext ctx) {
        LocalDate date;
        if (value instanceof Value.FunctionCall fn) {
            date = switch (fn.name().toLowerCase(Locale.ROOT)) {
                case "now" -> LocalDate.now(ZoneOffset.UTC);
                case "startofweek" -> LocalDate.now(ZoneOffset.UTC)
                        .with(ChronoField.DAY_OF_WEEK, 1); // ISO Monday
                default -> throw new HqlSemanticException(
                        "Function '" + fn.name() + "()' is not valid for field '" + field.name() + "'",
                        field.name());
            };
        } else {
            String raw = requireString(field, value);
            try {
                date = LocalDate.parse(raw);
            } catch (DateTimeParseException e) {
                throw new HqlSemanticException(
                        "Invalid date '" + raw + "'; expected YYYY-MM-DD", field.name());
            }
        }
        // Inclusive-day UTC window for timestamp fields (§6.3); DATE column uses date directly.
        Instant lower = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant upperExclusive = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return new ResolvedValue.DateBounds(date, lower, upperExclusive);
    }

    // ---- helpers ----

    /**
     * A {@link #requireString} operand that is also usable as a catalog-name lookup key
     * (HD-90). Every name→id map is keyed by {@link SearchNames#key(String)}, so the
     * lookups below apply the same function to the operand — a copy-pasted
     * {@code "2.4.0 "} or a non-breaking-space variant resolves like the plain name.
     *
     * <p>An operand that is <em>blank after normalization</em> ({@code fixVersion = "  "})
     * is rejected here with its own message rather than falling through to
     * "No version named '  '": the empty key is not a name anybody stored, and a catalog
     * row whose name normalized to {@code ""} would otherwise be a silent match for it.
     * Errors quote the RAW input — the user should see what they typed, not the folded
     * form.
     */
    private String requireName(FieldDescriptor field, Value value) {
        String raw = requireString(field, value);
        if (SearchNames.key(raw).isEmpty()) {
            throw new HqlSemanticException(
                    "Field '" + field.name() + "' expects a non-empty name", field.name());
        }
        return raw;
    }

    private String requireString(FieldDescriptor field, Value value) {
        if (value instanceof Value.StringLiteral s) {
            return s.value();
        }
        if (value instanceof Value.FunctionCall fn) {
            throw new HqlSemanticException(
                    "Function '" + fn.name() + "()' is not valid for field '" + field.name() + "'", field.name());
        }
        throw new HqlSemanticException(
                "Field '" + field.name() + "' expects a quoted value", field.name());
    }

    private UUID tryParseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
