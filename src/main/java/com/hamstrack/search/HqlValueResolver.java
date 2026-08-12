package com.hamstrack.search;

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
            // ISSUE_REF (parent) needs an issue lookup — the compiler routes it to
            // HqlParentResolver, so it never reaches this resolver.
            case ISSUE_REF -> throw new IllegalStateException(
                    "parent resolution is handled by HqlParentResolver");
            case DATE, TIMESTAMP -> resolveDate(field, value, ctx);
            case TEXT -> new ResolvedValue.TextTerm(requireString(field, value));
            case NUMBER -> throw new HqlSemanticException(
                    "Field '" + field.name() + "' is not queryable in MVP", field.name());
        };
    }

    /**
     * Resolve a {@code priority} operand for an ORDERED comparison ({@code >/</>=/<=})
     * to its catalog {@code position} (§5.3). Only {@code priority} reaches here.
     */
    public ResolvedValue.PositionValue resolvePriorityPosition(FieldDescriptor field, Value value,
                                                               ResolutionContext ctx) {
        String name = requireString(field, value);
        var matches = ctx.prioritiesByName().get(name.toLowerCase(Locale.ROOT));
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
        String name = requireString(field, value);
        var byName = switch (field.name()) {
            case "status" -> ctx.statusIdsByName();
            case "type" -> ctx.typeIdsByName();
            case "priority" -> ctx.priorityIdsByName();
            default -> throw new HqlSemanticException(
                    "Field '" + field.name() + "' is not yet queryable", field.name());
        };
        var ids = byName.get(name.toLowerCase(Locale.ROOT));
        if (ids == null || ids.isEmpty()) {
            throw new HqlSemanticException(
                    "No " + field.name() + " named '" + name + "' in this workspace", field.name());
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
