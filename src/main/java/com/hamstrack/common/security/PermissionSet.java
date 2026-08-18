package com.hamstrack.common.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * An immutable set of effective grants — <strong>the one authorization primitive</strong>
 * (roles-permissions-proposal §5.1, Rule P1). Resolved <em>once per request</em> by
 * {@code WorkspaceAccessService} and carried on {@code WorkspaceContext} /
 * {@code ProjectContext}; no service may re-query {@code workspace_members} or
 * {@code project_members} to answer an authorization question again.
 *
 * <p>A call site that wants to authorize writes one line:
 * <pre>{@code ctx.permissions().require(Permission.SPRINT_MANAGE);}</pre>
 * so the cost of authorization per request is <strong>constant</strong> — a handler that
 * checks six permissions costs exactly what one that checks none costs.
 *
 * <p><strong>The ownership modifier (§6.4).</strong> A grant is
 * {@code (permission, own_only)}. An unrestricted grant answers {@link #has(Permission)}
 * true for every object; an own-only grant answers it <em>false</em>, and
 * {@link #has(Permission, boolean)} true only when the caller passes {@code isOwn}. The
 * asymmetry is the point: {@code has(ISSUE_EDIT)} asks "may you edit anyone's issue?",
 * and nothing can accidentally answer it with an own-only grant.
 *
 * <p>Held internally as two {@link EnumSet}s rather than the wire strings, so a check is
 * a bit test and no string is built on the hot path; {@link #asWireStrings()} renders the
 * flat {@code ["issue.edit", "comment.edit:own"]} form the SPA consumes, in catalog order
 * so a response body is stable and diffable.
 *
 * <p>Instances are deeply immutable and safe to cache and to share across threads and
 * requests — {@code RolePermissionCache} does exactly that.
 */
public final class PermissionSet {

    /** The own-only marker in the wire form: {@code "issue.edit:own"}. */
    public static final String OWN_SUFFIX = ":own";

    private static final PermissionSet EMPTY =
            new PermissionSet(EnumSet.noneOf(Permission.class), EnumSet.noneOf(Permission.class));

    /**
     * The catalog, once. {@code Permission.values()} <em>clones</em> its backing array on
     * every call — the JLS requires it, because the array is mutable — and the three loops
     * below run it per containment check, i.e. once per ordered pair of roles in the derived
     * assignment block that {@code GET /roles} builds (HD-127 round-3 review). Private and
     * never handed out, so the reason values() copies does not apply to it.
     */
    private static final Permission[] CATALOG = Permission.values();

    /** @see #allOf(RoleScope) — declared after {@link #EMPTY}, which {@code build} returns. */
    private static final Map<RoleScope, PermissionSet> ALL_OF_SCOPE = allOfScope();

    /** Granted for every object. */
    private final Set<Permission> unrestricted;
    /** Granted only for objects the actor owns. Disjoint from {@link #unrestricted}. */
    private final Set<Permission> ownOnly;

    private PermissionSet(Set<Permission> unrestricted, Set<Permission> ownOnly) {
        this.unrestricted = unrestricted;
        this.ownOnly = ownOnly;
    }

    /**
     * No grants at all. This is what a project Viewer holds, and what a member with no
     * project role holds in a {@code STRICT} workspace — <em>not</em> an error state, and
     * never {@code null}: an absent permission set would make the SPA fall back to
     * permissive rendering (§12).
     */
    public static PermissionSet empty() {
        return EMPTY;
    }

    /** Build from stored grants. A grant whose permission is own-required is forced own-only. */
    public static PermissionSet of(Collection<Grant> grants) {
        var unrestricted = EnumSet.noneOf(Permission.class);
        var own = EnumSet.noneOf(Permission.class);
        for (var g : grants) {
            if (g.ownOnly() || g.permission().ownRequired()) {
                own.add(g.permission());
            } else {
                unrestricted.add(g.permission());
            }
        }
        own.removeAll(unrestricted);
        return build(unrestricted, own);
    }

    /**
     * An unrestricted grant of each of {@code permissions}, honouring
     * {@link Permission#ownRequired()} — so a set containing {@code comment.edit} yields an
     * own-only grant of it, never an unrestricted one (§17.3).
     *
     * <p>Backs the two workspace-scoped "in every project" grants:
     * {@link Permission#PROJECT_CURATE_ALL} ({@link Permission#projectCuration()}) and
     * {@link Permission#PROJECT_ADMINISTER_ALL} ({@link #allOf(RoleScope) allOf(PROJECT)}).
     */
    public static PermissionSet granting(Set<Permission> permissions) {
        var unrestricted = EnumSet.noneOf(Permission.class);
        var own = EnumSet.noneOf(Permission.class);
        for (var p : permissions) {
            (p.ownRequired() ? own : unrestricted).add(p);
        }
        return build(unrestricted, own);
    }

    /**
     * Every permission of one scope. Memoised per scope rather than rebuilt: this sits on
     * the request path (every project resolution for a holder of
     * {@link Permission#PROJECT_ADMINISTER_ALL} unions it), and a {@code PermissionSet} is
     * an immutable value that is safe to share.
     */
    public static PermissionSet allOf(RoleScope scope) {
        return ALL_OF_SCOPE.get(scope);
    }

    /**
     * The union of two sets, taking the <em>wider</em> grant for any permission both
     * hold — an unrestricted grant always beats an own-only one, never the reverse.
     */
    public PermissionSet union(PermissionSet other) {
        if (other.isEmpty()) return this;
        if (this.isEmpty()) return other;
        var unrestricted = EnumSet.copyOf(this.unrestricted);
        unrestricted.addAll(other.unrestricted);
        var own = EnumSet.noneOf(Permission.class);
        own.addAll(this.ownOnly);
        own.addAll(other.ownOnly);
        own.removeAll(unrestricted);
        return build(unrestricted, own);
    }

    /** Whether this grants {@code permission} for <strong>any</strong> object. */
    public boolean has(Permission permission) {
        return unrestricted.contains(permission);
    }

    /**
     * Whether this grants {@code permission} for a specific object.
     *
     * @param isOwn whether the actor owns that object — the issue's reporter, the
     *              comment's author, the attachment's uploader, the label's creator
     *              (§6.4). Computed by the call site, never by this class.
     */
    public boolean has(Permission permission, boolean isOwn) {
        return unrestricted.contains(permission) || (isOwn && ownOnly.contains(permission));
    }

    /** @throws MissingPermissionException 403, naming the permission. */
    public void require(Permission permission) {
        if (!has(permission)) throw new MissingPermissionException(permission);
    }

    /** @throws MissingPermissionException 403, naming the permission. */
    public void require(Permission permission, boolean isOwn) {
        if (!has(permission, isOwn)) throw new MissingPermissionException(permission);
    }

    /** Whether this grants {@code permission} either unrestricted or own-only. */
    public boolean hasAtAll(Permission permission) {
        return unrestricted.contains(permission) || ownOnly.contains(permission);
    }

    /**
     * The first grant in {@code other} that this set does <strong>not</strong> cover, in
     * catalog order — {@link Optional#empty()} when this set is at least as wide as
     * {@code other} everywhere.
     *
     * <p>This is the <strong>grant ceiling</strong> of §11.2 expressed on permission sets
     * rather than on a role ladder: nobody may hand out a role holding something they do
     * not hold themselves, which is what stops {@code project.member.manage} (or
     * {@code workspace.member.manage}) from being self-escalation to everything. A role
     * ladder could express it with {@code isAtLeast}; custom roles have no ladder, so the
     * comparison has to be set containment.
     *
     * <p>Width is compared per grant, not just presence: an own-only grant in
     * {@code other} is covered by an own-only grant here, but an <em>unrestricted</em>
     * grant in {@code other} is not covered by an own-only one here — otherwise a role
     * holding {@code comment.delete:own} could mint one holding it unrestricted.
     *
     * <p>Returns the offending permission rather than a boolean so the 403 can name it:
     * "you cannot grant this role, it includes {@code issue.delete}" is actionable where
     * "insufficient role" is not.
     */
    public Optional<Permission> firstNotCovered(PermissionSet other) {
        for (var p : CATALOG) {
            if (!covers(other, p)) return Optional.of(p);
        }
        return Optional.empty();
    }

    /**
     * <strong>Every</strong> grant in {@code other} this set does not cover, in catalog
     * order and in the {@link #asWireStrings() wire form} — the same containment rule as
     * {@link #firstNotCovered}, enumerated rather than short-circuited.
     *
     * <p>Exists for one purpose: an audit line that can say <em>what</em> a role edit
     * granted. A bulk widening of a role is a privilege grant to every holder at once, and
     * {@code role.updated permissionsReplaced=true} records that it happened without
     * recording what it did. Do not use it to authorize — a decision asks
     * {@link #firstNotCovered}, which is the one predicate the ceiling, the derived
     * assignment block and the 403 message all share.
     */
    public List<String> allNotCovered(PermissionSet other) {
        var out = new ArrayList<String>();
        for (var p : CATALOG) {
            if (!covers(other, p)) {
                out.add(other.unrestricted.contains(p) ? p.key() : p.key() + OWN_SUFFIX);
            }
        }
        return List.copyOf(out);
    }

    /**
     * The single containment rule both of the above ask, so the "what is missing" list and
     * the "is anything missing" decision can never disagree about width.
     */
    private boolean covers(PermissionSet other, Permission p) {
        if (other.unrestricted.contains(p)) return unrestricted.contains(p);
        if (other.ownOnly.contains(p)) return hasAtAll(p);
        return true;
    }

    public boolean isEmpty() {
        return unrestricted.isEmpty() && ownOnly.isEmpty();
    }

    /**
     * The flat wire form the SPA gates on: {@code "issue.edit"} for an unrestricted
     * grant, {@code "comment.edit:own"} for an own-only one. Catalog order, so the JSON
     * is stable across requests and installs.
     */
    public List<String> asWireStrings() {
        var out = new ArrayList<String>(unrestricted.size() + ownOnly.size());
        for (var p : CATALOG) {
            if (unrestricted.contains(p)) out.add(p.key());
            else if (ownOnly.contains(p)) out.add(p.key() + OWN_SUFFIX);
        }
        return List.copyOf(out);
    }

    @Override
    public String toString() {
        return "PermissionSet" + asWireStrings();
    }

    private static Map<RoleScope, PermissionSet> allOfScope() {
        var map = new EnumMap<RoleScope, PermissionSet>(RoleScope.class);
        for (var scope : RoleScope.values()) {
            map.put(scope, granting(Permission.of(scope)));
        }
        return Collections.unmodifiableMap(map);
    }

    private static PermissionSet build(Set<Permission> unrestricted, Set<Permission> own) {
        if (unrestricted.isEmpty() && own.isEmpty()) return EMPTY;
        return new PermissionSet(unrestricted, own);
    }

    /** One stored row of {@code role_permissions}, decoupled from the JPA embeddable. */
    public record Grant(Permission permission, boolean ownOnly) {}
}
