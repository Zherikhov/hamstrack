package com.hamstrack.common.security;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * <strong>The permission catalog — the single source of truth for what a Hamstrack user
 * may do</strong> (roles-permissions-proposal §6). 29 entries: 9 workspace-scoped, 20
 * project-scoped.
 *
 * <p><strong>There is no {@code permissions} table.</strong> A permission without a call
 * site is meaningless, and call sites are code, so a table could only ever drift from
 * this enum. What the database stores is <em>grants</em> — rows in
 * {@code role_permissions} keyed by the {@link #key()} string, validated on read by
 * {@link PermissionConverter}. Adding a permission is therefore one constant here, one
 * {@code require(...)} line at the call site, and one row per built-in role that should
 * hold it. <strong>Never a migration.</strong>
 *
 * <p><strong>Keys are wire format and are permanent.</strong> {@code area.action},
 * lowercase, dot-separated, ≤64 chars. They travel to the SPA in {@code myPermissions}
 * and are what a UI gate compares against, so renaming one is a breaking change for
 * every client and every stored custom role. Add a new key and migrate instead.
 *
 * <p><strong>Three things this enum is not:</strong>
 * <ul>
 *   <li><em>A capability.</em> {@link #capability()} is a hint for the role editor, never
 *       a check — see {@link CapabilityHint}.</li>
 *   <li><em>A visibility rule.</em> There is deliberately no {@code issue.view} /
 *       {@code project.view} in v1 (§3 non-goals): shipping a permission every role must
 *       have is dead code that lies. When they land (§8.5), their failure mode must be
 *       <strong>404, never 403</strong> — a permission that governs visibility must not
 *       disclose that the resource exists.</li>
 *   <li><em>A role.</em> Roles compose permissions; nothing here has an ordinal or a
 *       total order. The old {@code isAtLeast} ladders are what this catalog replaces.</li>
 * </ul>
 */
public enum Permission {

    // ======================================================= workspace scope (9)

    /**
     * Rename/describe the workspace; set the project-access mode and the workspace
     * default project role.
     */
    WORKSPACE_EDIT("workspace.edit", RoleScope.WORKSPACE),

    /** Invite people, change a member's workspace role, revoke pending invites. */
    WORKSPACE_MEMBER_MANAGE("workspace.member.manage", RoleScope.WORKSPACE),

    /**
     * Create/edit/delete <strong>custom roles</strong> in this workspace. Deliberately
     * separate from {@link #WORKSPACE_MEMBER_MANAGE}: defining what a role may do is
     * strictly more dangerous than handing out an existing one.
     */
    WORKSPACE_ROLE_MANAGE("workspace.role.manage", RoleScope.WORKSPACE),

    /**
     * The workspace-scoped catalog &amp; sets and the project-binding matrix
     * ({@code /api/workspaces/{ws}/admin/**}). Replaced the deleted
     * {@code ScopeResolver.requireWorkspaceAdmin} in HD-126 (S3).
     */
    WORKSPACE_TAXONOMY_MANAGE("workspace.taxonomy.manage", RoleScope.WORKSPACE),

    /**
     * Create a project in this workspace. Workspace-scoped despite the {@code project.}
     * prefix — there is no project to scope it to yet.
     */
    PROJECT_CREATE("project.create", RoleScope.WORKSPACE),

    /**
     * Hold <strong>today's workspace-admin curator bypass</strong> in every project of the
     * workspace, without being a project member: exactly
     * {@link #projectCuration() {project.edit, component.manage, version.manage,
     * sprint.manage}} — the 16 {@code ScopeResolver.requireProjectCurator} call sites and
     * nothing else (§17.2).
     *
     * <p><strong>This, not {@link #PROJECT_ADMINISTER_ALL}, is what the built-in Owner and
     * Admin hold.</strong> The bypass they have today is narrow, and the parity harness
     * proved it: {@code project.archive}, {@code project.member.manage},
     * {@code project.taxonomy.manage}, {@code issue.delete} and unrestricted
     * {@code attachment.delete} are project-MANAGER-only, and a workspace Owner with no
     * {@code project_members} row is refused all five right now
     * ({@code ProjectService}/{@code AttachmentService} say so in as many words). Seeding
     * the built-ins with {@code project.administer.all} would have widened five gates for
     * every Owner and Admin in every install — the "mapped a notch too loose" failure §18
     * calls the worse of the two.
     */
    PROJECT_CURATE_ALL("project.curate.all", RoleScope.WORKSPACE),

    /**
     * Hold every project permission in <strong>every</strong> project of the workspace,
     * without being a project member — the strict superset of {@link #PROJECT_CURATE_ALL}.
     *
     * <p><strong>Deliberately not held by any built-in role</strong> (§7.1): it is wider
     * than anything that exists today, so seeding it would change what an Owner may do.
     * It stays in the catalog because it is the whole point of making the bypass explicit
     * (§17.2) — a workspace can build a "Program manager" custom role that administers
     * every project without being able to invite people, which the hardcoded
     * {@code if (workspaceRole.isAtLeast(ADMIN)) return;} could never express.
     *
     * <p>The implied grant respects {@link #ownRequired()}: holding this does
     * <em>not</em> give unrestricted {@code comment.edit}, because editing another
     * person's words is not a capability this product ships at any role (§17.3).
     */
    PROJECT_ADMINISTER_ALL("project.administer.all", RoleScope.WORKSPACE),

    /** Create a workspace label. Everyday action; separate from curation on purpose. */
    LABEL_CREATE("label.create", RoleScope.WORKSPACE),

    /**
     * Rename/recolor/describe/archive/merge/delete <strong>any</strong> label.
     * {@code own} = only labels you created — which is exactly today's
     * {@code LabelService.requireEditor} fallback, and why the built-in workspace
     * <strong>Member</strong> holds this own-only (§7.1).
     *
     * <p><strong>The own/unrestricted split must land on the split the code already
     * has</strong> — one key, two call sites with different arities:
     * <ul>
     *   <li>{@code LabelService.requireEditor} (rename / recolor / describe) is
     *       {@code require(LABEL_MANAGE, isOwn)} — a creator passes on their own label;</li>
     *   <li>{@code LabelService.requireCurator} (archive / unarchive / merge / delete) is
     *       {@code require(LABEL_MANAGE)} <em>with no ownership argument</em>, so an
     *       own-only grant can never satisfy it — which is today's rule exactly: a member
     *       may rename the label they created, and may not archive it.</li>
     * </ul>
     * Passing {@code isOwn} at the curator sites when S3 moves them would silently hand
     * every workspace member archive/merge/delete over their own labels. That is the one
     * way to get this entry wrong, and it is a code review item, not a catalog one.
     */
    LABEL_MANAGE("label.manage", RoleScope.WORKSPACE, null, Own.OPTIONAL),

    // ========================================================= project scope (20)

    /** Rename/describe the project; change its delivery capabilities. */
    PROJECT_EDIT("project.edit", RoleScope.PROJECT),

    /** Archive / unarchive the project. */
    PROJECT_ARCHIVE("project.archive", RoleScope.PROJECT),

    /**
     * Add/remove project members, change their project role, set the project's default
     * role.
     */
    PROJECT_MEMBER_MANAGE("project.member.manage", RoleScope.PROJECT),

    /**
     * Project-private catalog &amp; sets and this project's bindings
     * ({@code /api/workspaces/{ws}/projects/{p}/admin/**}).
     */
    PROJECT_TAXONOMY_MANAGE("project.taxonomy.manage", RoleScope.PROJECT),

    /** File a new issue. */
    ISSUE_CREATE("issue.create", RoleScope.PROJECT),

    /**
     * Change any issue field that is not covered by a more specific permission: title,
     * description, type, priority, labels, component, versions, parent, due date, story
     * points, custom fields. {@code own} = issues you reported (§19 OQ 2: the reporter,
     * not the assignee, in v1).
     */
    ISSUE_EDIT("issue.edit", RoleScope.PROJECT, null, Own.OPTIONAL),

    /**
     * Change an issue's status (board drag, status picker). The single most-requested
     * split.
     */
    ISSUE_TRANSITION("issue.transition", RoleScope.PROJECT),

    /** Set or clear an issue's assignee (including self-assign). */
    ISSUE_ASSIGN("issue.assign", RoleScope.PROJECT),

    /**
     * <strong>May be chosen as an assignee.</strong> The only permission that is about
     * being a <em>subject</em> rather than acting, so note the asymmetry:
     * {@link #ISSUE_ASSIGN} is checked against the actor, this one against the target.
     * Without it you cannot express "contractors comment but are never given work", and
     * cannot keep read-only accounts out of the assignee picker.
     */
    ISSUE_ASSIGNABLE("issue.assignable", RoleScope.PROJECT),

    /**
     * Reorder the backlog/board rank ({@code issues.position}). Ranking is a planning
     * act, and "developers must not reprioritise the backlog" is a real policy.
     */
    ISSUE_RANK("issue.rank", RoleScope.PROJECT),

    /** Delete an issue. {@code own} = issues you reported. */
    ISSUE_DELETE("issue.delete", RoleScope.PROJECT, null, Own.OPTIONAL),

    /** Post a comment. */
    COMMENT_CREATE("comment.create", RoleScope.PROJECT),

    /**
     * Edit a comment. <strong>Own-only, and not grantable unrestricted in v1</strong>
     * (§17.3) — putting words in someone else's mouth is not a permission we ship at any
     * role. {@link #COMMENT_DELETE} unrestricted covers the real moderation need.
     */
    COMMENT_EDIT("comment.edit", RoleScope.PROJECT, null, Own.REQUIRED),

    /**
     * Delete a comment. {@code own} = your own; unrestricted = moderation (new — today
     * nobody can delete another person's comment, §10.3.5).
     */
    COMMENT_DELETE("comment.delete", RoleScope.PROJECT, null, Own.OPTIONAL),

    /** Upload a file to an issue. */
    ATTACHMENT_CREATE("attachment.create", RoleScope.PROJECT),

    /**
     * Delete an attachment. {@code own} = files you uploaded — exactly today's rule;
     * unrestricted ≈ today's project {@code MANAGER}.
     */
    ATTACHMENT_DELETE("attachment.delete", RoleScope.PROJECT, null, Own.OPTIONAL),

    /** Create / rename / start / complete / delete sprints. */
    SPRINT_MANAGE("sprint.manage", RoleScope.PROJECT, CapabilityHint.BOARD, Own.NONE),

    /**
     * Put issues into / take issues out of a sprint. Separate from
     * {@link #SPRINT_MANAGE} because <strong>today they differ</strong> (lifecycle is
     * curator-gated, assignment is open to any member) and reproducing today's behaviour
     * requires the split.
     *
     * <p><strong>Double-door rule (§6.5):</strong> this governs {@code POST
     * /sprints/{id}/issues}, {@code DELETE /sprints/{id}/issues/{issueId}} AND
     * {@code PATCH /issues/{n}} when the body carries {@code sprintId}. A permission is
     * only real if every endpoint that can produce the effect checks it.
     */
    SPRINT_ASSIGN("sprint.assign", RoleScope.PROJECT, CapabilityHint.BOARD, Own.NONE),

    /**
     * Create / edit / release / unrelease / archive / delete versions. Governs the
     * version's <em>lifecycle</em>, not its assignment to an issue — setting
     * {@code fixVersionIds} is {@link #ISSUE_EDIT} (§6.5).
     */
    VERSION_MANAGE("version.manage", RoleScope.PROJECT, CapabilityHint.RELEASES, Own.NONE),

    /**
     * Create / edit / archive / delete components. As with versions, setting
     * {@code componentId} on an issue is {@link #ISSUE_EDIT}, not this.
     */
    COMPONENT_MANAGE("component.manage", RoleScope.PROJECT);

    /** How a catalog entry relates to the ownership modifier (§6.4). */
    private enum Own {
        /** The permission is all-or-nothing; {@code own_only} must be false. */
        NONE,
        /** May be granted unrestricted or narrowed to objects the actor owns. */
        OPTIONAL,
        /** May only ever be granted own-only. Unrestricted is not expressible. */
        REQUIRED
    }

    /**
     * {@code area.action}, lowercase, dot-separated, at least two segments. Enforced at
     * class-init rather than trusted, because {@link PermissionSet#OWN_SUFFIX} makes the
     * wire form {@code key + ":own"}: a key containing a {@code ':'} would make that
     * encoding <strong>ambiguous</strong>, and a client parsing it with
     * {@code startsWith}/{@code split} resolves the ambiguity in the <em>widening</em>
     * direction (reading an own-only grant as unrestricted). Uppercase is refused for a
     * related reason — an {@code Enum.name()} pasted in as a key is caught here instead of
     * silently becoming the stored and published value.
     *
     * <p>Declared <strong>before</strong> {@link #BY_KEY}: static initialisers run in
     * textual order, and {@code indexByKey()} reads this.
     */
    private static final Pattern KEY_FORMAT = Pattern.compile("[a-z]+(\\.[a-z]+)+");

    private static final Map<String, Permission> BY_KEY = indexByKey();

    /** @see #projectCuration() */
    private static final Set<Permission> PROJECT_CURATION = Collections.unmodifiableSet(
            EnumSet.of(PROJECT_EDIT, COMPONENT_MANAGE, VERSION_MANAGE, SPRINT_MANAGE));

    private final String key;
    private final RoleScope scope;
    private final CapabilityHint capability;
    private final Own own;

    Permission(String key, RoleScope scope) {
        this(key, scope, null, Own.NONE);
    }

    Permission(String key, RoleScope scope, CapabilityHint capability, Own own) {
        this.key = key;
        this.scope = scope;
        this.capability = capability;
        this.own = own;
    }

    /**
     * The stored and wire form ({@code "sprint.manage"}). <strong>Not</strong>
     * {@link #name()}: the constant name is a Java identifier and the key is the
     * product's vocabulary, and conflating them would make {@code @Enumerated(STRING)}
     * write {@code SPRINT_MANAGE} into a column the SPA reads as {@code sprint.manage}.
     */
    public String key() {
        return key;
    }

    public RoleScope scope() {
        return scope;
    }

    /** The delivery capability this permission is about, or {@code null}. A hint only. */
    public CapabilityHint capability() {
        return capability;
    }

    /** Whether {@code own_only} may be set on a grant of this permission. */
    public boolean supportsOwn() {
        return own != Own.NONE;
    }

    /**
     * Whether {@code own_only} <em>must</em> be set — true only for
     * {@link #COMMENT_EDIT}. The role editor renders this as a locked "own only" toggle,
     * and {@link Permission#PROJECT_ADMINISTER_ALL}'s implied grant honours it.
     */
    public boolean ownRequired() {
        return own == Own.REQUIRED;
    }

    /**
     * The catalog entry for a stored key. Empty for an unknown key, which is a hard
     * error everywhere it can occur: on a write it is a 422 ("Unknown permission"), and
     * on a read it means the database holds a grant this build no longer understands —
     * silently dropping it would quietly widen or narrow a role.
     */
    public static java.util.Optional<Permission> byKey(String key) {
        return java.util.Optional.ofNullable(BY_KEY.get(key));
    }

    /** The whole catalog, in declaration order (workspace scope first). */
    public static List<Permission> catalog() {
        return List.of(values());
    }

    /**
     * What {@link #PROJECT_CURATE_ALL} implies — <strong>the curator set</strong>:
     * {@code project.edit}, {@code component.manage}, {@code version.manage},
     * {@code sprint.manage}.
     *
     * <p>Read off the code, not off a wish list: these are the permissions the 16
     * {@code ScopeResolver.requireProjectCurator} call sites become (§10.2/§10.4) —
     * {@code ProjectService.update} ×1, {@code ComponentService} ×4,
     * {@code VersionService} ×6, {@code SprintService} ×5 — and {@code requireProjectCurator}
     * is the <em>only</em> place a workspace OWNER/ADMIN bypasses the project role today.
     * Adding an entry here widens what every Owner and Admin in every install may do
     * without being a project member; do it only with the same deliberation as a seed
     * change, and make {@code BuiltInRoleSeedParityTest} agree first.
     */
    public static Set<Permission> projectCuration() {
        return PROJECT_CURATION;
    }

    /** Every permission of one scope, in declaration order. */
    public static Set<Permission> of(RoleScope scope) {
        var set = EnumSet.noneOf(Permission.class);
        for (var p : values()) {
            if (p.scope == scope) set.add(p);
        }
        return Collections.unmodifiableSet(set);
    }

    private static Map<String, Permission> indexByKey() {
        var map = new HashMap<String, Permission>();
        for (var p : values()) {
            if (map.put(p.key, p) != null) {
                throw new IllegalStateException("Duplicate permission key: " + p.key);
            }
            if (p.key.length() > 64) {
                throw new IllegalStateException("Permission key exceeds VARCHAR(64): " + p.key);
            }
            if (!KEY_FORMAT.matcher(p.key).matches()) {
                throw new IllegalStateException(
                        "Permission key '" + p.key + "' (" + p.name() + ") is not area.action, "
                        + "lowercase, dot-separated. Keys are wire format and permanent, and a ':' "
                        + "in one would make the \"" + PermissionSet.OWN_SUFFIX + "\" suffix "
                        + "ambiguous for every client that parses it.");
            }
        }
        return Map.copyOf(map);
    }
}
