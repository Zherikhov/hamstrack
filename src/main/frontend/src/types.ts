export interface User {
  id: string;
  email: string;
  displayName: string;
  avatarUrl?: string;
  // Instance-wide role; ADMIN unlocks /admin (server enforces regardless)
  systemRole?: 'ADMIN' | 'USER';
  // Cloud only: true until the user creates/joins their first team (or skips)
  needsOnboarding?: boolean;
}

// A pending workspace invite addressed to the current user (onboarding "join")
export interface PendingInvite {
  id: string;
  workspaceId: string;
  workspaceName: string;
  role: 'OWNER' | 'ADMIN' | 'MEMBER';
  invitedByName: string;
  createdAt: string;
  expiresAt: string;
}

export interface Workspace {
  id: string;
  name: string;
  slug: string;
  /**
   * DISPLAY ONLY (HD-123 §5.3). The caller's workspace role as a label — the
   * People table, the workspace card. **Never a UI gate:** `myRole === 'ADMIN'`
   * cannot express a custom role, so a component that decides anything from it
   * is wrong by construction. Widened from a literal union for exactly that
   * reason — there is nothing here to switch on. Gate on `myPermissions` via
   * `hooks/usePermissions`.
   */
  myRole: string;
  /**
   * The caller's effective WORKSPACE permissions as flat grant keys
   * (`["workspace.edit", "label.manage:own"]`) — the only permitted input to a
   * UI gate. Always sent (an empty array is a real answer); required here so a
   * hand-built object cannot silently mean "unknown", which would deny.
   */
  myPermissions: string[];
  /**
   * Whether workspace members who were never added to a project inherit its
   * default role (HD-130, S7). **This is the only place it is published** — a
   * workspace-level fact with one source of truth, read by the project People
   * card from the `['workspace', wsId]` entry every surface already caches.
   *
   * It decides exactly one thing: whether the default-access chain yields a role
   * at all. It is **not** an on/off switch for permissions, and there is
   * deliberately no "enforcement off" mode. `OPEN` is the identity — a workspace
   * that never opens the General page behaves exactly as it did before S7.
   *
   * Optional on the wire only for fixtures predating S7; absent is read as
   * `OPEN`, which is the value every existing workspace was migrated with.
   */
  projectAccessMode?: ProjectAccessMode;
  /**
   * The workspace's declared default project role, or `null` for the built-in
   * Contributor. A raw **id, never a name**: it is a plain FK the database cannot
   * constrain to "a PROJECT role of THIS workspace", so a client that cannot find
   * it in `GET …/roles` renders a placeholder rather than a guess.
   *
   * It exists in **both** modes. In `STRICT` it is inert but stored, and it goes
   * live again the moment the mode is flipped back.
   */
  defaultProjectRoleId?: string | null;
  createdAt: string;
}

/**
 * Whether the default-access chain yields a role for a workspace member with no
 * explicit `project_members` row (HD-130, S7).
 *
 * `OPEN` — they inherit the project's declared default. `STRICT` — they inherit
 * nothing, and only people explicitly added to a project can change anything in
 * it. **Reads are never narrowed by either**: everyone still sees every project,
 * because narrowing reads would be private projects by the back door.
 */
export type ProjectAccessMode = 'OPEN' | 'STRICT';

// Presentation switch (HD-22 §3.5), NOT a permission: the sprint API works
// identically in both modes. SCRUM scopes the board to the active sprint and
// shows the sprint header; KANBAN is the pre-0.13.0 behaviour.
export type BoardMode = 'KANBAN' | 'SCRUM';

// The DERIVED label for a capability set (HD-102 §2.3). Computed server-side
// from the three capabilities and never stored, never sent back in a request —
// `delivery.preset` is rejected with a 400 on write.
export type DeliveryPreset = 'KANBAN' | 'SCRUM' | 'RELEASES' | 'CUSTOM';

/**
 * How this team delivers (HD-102 §2.3) — the DECLARED capability set, and the
 * single answer to "does this project do X?".
 *
 * Rule B (§5.2): these gate CONTROLS only. A value an issue already carries (a
 * sprint, a fix version, a story-point estimate) is rendered whenever it exists,
 * read-only, regardless of the capability — switching a capability off is
 * provably non-destructive and must also be legible.
 *
 * Rule C (§5.3): no surface may infer a capability from the presence of data.
 * `openSprints.length > 0` / `sprintOptions.length > 0` / `versionOptions.length
 * > 0` are NOT answers to this question.
 */
export interface ProjectDelivery {
  board: BoardMode;      // iterations: sprint sections, sprint-scoped board, sprint pickers
  releases: boolean;     // versions: the Releases page and rail item, fix/affects pickers
  estimation: boolean;   // story points: the points input and every point sum
  preset: DeliveryPreset;
}

/** The write half — `preset` is derived and REJECTED on write, so it is absent. */
export type ProjectDeliveryUpdate = Partial<Omit<ProjectDelivery, 'preset'>>;

export interface Project {
  id: string;
  workspaceId: string;
  name: string;
  key: string;
  description?: string;
  archived: boolean;
  /**
   * DISPLAY ONLY (HD-123 §5.3) — see `Workspace.myRole`. It reports the caller's
   * EXPLICIT project role and deliberately says `VIEWER` for a member who has no
   * `project_members` row but inherits a full Contributor set from the project
   * access default, so it is not even a faithful summary of what they may do.
   * `myPermissions` is. Gate on `hooks/usePermissions`, never on this.
   */
  myRole: string;
  /**
   * The caller's effective PROJECT permissions as flat grant keys — the only
   * permitted input to a UI gate. Already includes the workspace-level curator
   * bypass (`project.curate.all`), so a project gate needs no workspace lookup.
   */
  myPermissions: string[];
  /**
   * @deprecated (HD-102) superseded by `delivery.board`, which carries the same
   * value. Read `delivery` instead — never both. The only place this mirror is
   * still consulted is `deliveryOf()`, as the fallback for a response (or a
   * hand-built fixture) that predates `delivery`.
   */
  boardMode?: BoardMode;
  // Optional on the wire only for pre-HD-102 responses / hand-built fixtures —
  // every consumer goes through `deliveryOf()` / `useProjectDelivery`.
  delivery?: ProjectDelivery;
  /**
   * Who gets what here **without** an explicit project membership — for most
   * workspaces the primary grant mechanism, since almost nobody has a
   * `project_members` row. Optional only for fixtures predating HD-129.
   */
  defaultRole?: ProjectDefaultRole;
  createdAt: string;
}

/**
 * Both links of the default-access chain — project override → workspace default
 * → the built-in Contributor — so a card can say not just *what* people get but
 * *where that came from*. Read-only: the picker and the grant ceiling that has
 * to bound it ship together in S7, and a control that always fails is worse than
 * none.
 *
 * Each id is emitted verbatim from a plain FK column the database cannot
 * constrain to "a PROJECT role of THIS workspace", so an id that is not in
 * `GET …/roles` renders a placeholder rather than a guess.
 */
export interface ProjectDefaultRole {
  /** This project's own override, or `null` when it inherits the workspace default. */
  projectRoleId: string | null;
  /** The workspace-level default, or `null` when the chain falls through to Contributor. */
  workspaceRoleId: string | null;
}

export interface IssueType {
  id: string;
  name: string;
  color: string;
  icon?: string;
  position: number;
  // Hierarchy: higher = higher in the tree (Epic=2, Story/Task/Bug=1, Sub-task=0).
  // A parent's type level must be STRICTLY GREATER than its child's. Config-driven.
  hierarchyLevel: number;
}

export interface Status {
  id: string;
  name: string;
  color: string;
  category: 'TODO' | 'IN_PROGRESS' | 'DONE';
  position: number;
}

// Catalog entity since M1 (was a closed enum before)
export interface Priority {
  id: string;
  name: string;
  color: string;
  icon?: string;
  position?: number;
}

// The effective taxonomy of a project — the board, filters and issue forms
// render exclusively from this
export interface ProjectConfig {
  statuses: Status[];                 // board-column order
  transitions: TransitionRule[];      // empty = all moves allowed
  priorities: PriorityOption[];       // display order
  issueTypes: IssueType[];
  fields: ProjectField[];             // custom fields, display order
}

// ── Custom fields (M2) ──────────────────────────────────────────────────────

export type FieldType =
  | 'TEXT' | 'TEXTAREA' | 'NUMBER' | 'DATE'
  | 'SELECT' | 'MULTI_SELECT' | 'USER' | 'CHECKBOX' | 'URL';

// Value JSON shape per type: TEXT/TEXTAREA/URL string · NUMBER number ·
// DATE "YYYY-MM-DD" · SELECT option id · MULTI_SELECT option id[] ·
// USER user UUID · CHECKBOX boolean
export type FieldValue = string | number | boolean | string[];

export interface FieldConfig {
  options?: { id: string; label: string; color?: string }[];  // selects
  min?: number;                                               // numbers
  max?: number;
}

// One custom field as a project offers it (definition + set flags)
export interface ProjectField {
  id: string;
  key: string;
  name: string;
  type: FieldType;
  config?: FieldConfig | null;
  description?: string;
  required: boolean;
  showOnCreate: boolean;
}

export interface FieldValueEntry {
  fieldId: string;
  value: FieldValue;
}

export interface TransitionRule {
  fromStatusId: string | null;        // null = "from any status"
  toStatusId: string;
}

export interface PriorityOption extends Priority {
  isDefault: boolean;
}

export interface AssigneeInfo {
  id: string;
  displayName: string;
  avatarUrl?: string;
}

// ── Labels (HD-30) ──────────────────────────────────────────────────────────
// Workspace-scoped, colored, many-per-issue. Deliberately NOT part of
// ProjectConfig: labels are content, not bound taxonomy, so they live under
// their own query key (`['labels', wsId]`) and never invalidate the board's
// config fetch when someone recolors one.

/** A label as embedded in an issue payload — id/name/color + archived (dimmed). */
export interface LabelRef {
  id: string;
  name: string;
  color: string;
  archived: boolean;
}

/** A full workspace label row (settings page / picker source). */
export interface Label extends LabelRef {
  description?: string;
  createdById?: string;
  createdByName?: string;
  /** null unless the caller asked for usage (`withUsage=true` / the /usage endpoint). */
  issueCount: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface MergeLabelsResult {
  targetId: string;
  mergedLabelCount: number;
  reassignedIssueCount: number;
}

// ── Components (HD-31) ──────────────────────────────────────────────────────
// Project-scoped modules ("Billing", "iOS app") with an optional lead and an
// optional auto-assign switch, ONE per issue. Like labels they are content, not
// bound taxonomy — they never travel in ProjectConfig and live under their own
// query key (`['components', wsId, projectId]`).

/** A component as embedded in an issue payload — enough to render it (dimmed when archived). */
export interface ComponentRef {
  id: string;
  name: string;
  archived: boolean;
}

/** A full project component row (settings page / picker source). */
export interface Component extends ComponentRef {
  description?: string;
  /** The lead may have left the workspace — the row survives and auto-assign silently skips. */
  leadId?: string;
  leadName?: string;
  leadAvatarUrl?: string;
  /** Assign new issues to the lead when the create request carries no assignee. */
  autoAssign: boolean;
  /** null unless the caller asked for usage (`withUsage=true` / the /usage endpoint). */
  issueCount: number | null;
  createdAt: string;
  updatedAt: string;
}

// ── Versions (HD-32) ────────────────────────────────────────────────────────
// Project-scoped release targets with a lifecycle (unreleased ⇄ released, plus
// an orthogonal archived). Issues link to them in TWO roles — fix version ("the
// change ships here") and affects version ("the defect exists there") — both
// many-to-many. Like labels and components they are content, not bound taxonomy:
// their own endpoint, their own query key (`['versions', wsId, projectId]`),
// never part of ProjectConfig.

/** A version as embedded in an issue payload — enough to render it (dimmed when archived). */
export interface VersionRef {
  id: string;
  name: string;
  released: boolean;
  archived: boolean;
}

/** A full project version row (Releases page / picker source). */
export interface Version extends VersionRef {
  description?: string;
  /** Planned/actual release date, `YYYY-MM-DD`. Preserved when un-releasing. */
  releaseDate?: string;
  /** Instant the version was flipped to released; null while unreleased. */
  releasedAt?: string;
  /** FIX-link progress — ALWAYS present (one grouped query for the whole list),
   *  so the Releases page needs no extra round-trip per card. */
  issueCount: number;
  doneIssueCount: number;
  /** Issues that merely *affect* this version (triage), not part of progress. */
  affectsIssueCount: number;
  createdAt: string;
  updatedAt: string;
}

/** `GET …/versions/{id}/usage` — drives the delete dialog and the release dialog. */
export interface VersionUsage {
  fixIssueCount: number;
  affectsIssueCount: number;
  /** FIX-linked issues NOT in a DONE-category status — the "move N unresolved" count. */
  unresolvedFixIssueCount: number;
}

// ── Sprints (HD-22 / HD-23 / HD-27) ─────────────────────────────────────────
// Project-scoped iterations with a real lifecycle (FUTURE → ACTIVE → COMPLETED,
// no re-open). Like components and versions they are project CONTENT, not bound
// taxonomy: their own endpoints, their own query keys (`sprintsKey`), never part
// of `ProjectConfig` — so starting a sprint does not invalidate the config every
// board render depends on.

export type SprintState = 'FUTURE' | 'ACTIVE' | 'COMPLETED';

/** A sprint as embedded in an issue payload — enough to render the badge. */
export interface SprintRef {
  id: string;
  name: string;
  state: SprintState;
}

/** A full sprint row (planning sections, board header, pickers). */
export interface Sprint extends SprintRef {
  goal?: string | null;
  /** 1-based, per project; drives the default name and the display order. */
  sequence: number;
  startAt?: string | null;
  endAt?: string | null;
  completedAt?: string | null;
  /** ACTIVE + `endAt` only; negative = overdue, 0 = ends today, null otherwise. */
  daysRemaining?: number | null;
  issueCount: number;
  doneIssueCount: number;
  /** null in the story-points fallback design (§3.4) — every point UI degrades to hidden. */
  points: number | null;
  donePoints: number | null;
  unestimatedCount: number;
  createdAt: string;
  updatedAt: string;
}

/**
 * Whole-section totals. ALWAYS computed over the entire section server-side,
 * never over a truncated page — a truncated section still shows honest numbers.
 */
export interface SectionStats {
  issueCount: number;
  doneIssueCount: number;
  points: number | null;
  donePoints: number | null;
  unestimatedCount: number;
}

/** Common shape of one planning section (the HD-79 truncation pattern). */
export interface BacklogSectionBase {
  issues: Issue[];
  truncated: boolean;
  totalAvailable: number;
  stats: SectionStats;
}

export interface BacklogSprintSection extends BacklogSectionBase {
  sprint: Sprint;
}

/** `GET …/backlog` — the whole planning view in one aggregate. */
export interface BacklogView {
  /** ACTIVE first, then FUTURE by sequence. */
  sprints: BacklogSprintSection[];
  backlog: BacklogSectionBase;
  /** `app.agile.section-max-issues` — the per-section cap the server applied. */
  sectionCap: number;
  /**
   * `app.agile.max-issues-per-bulk-move` — the largest `issueIds` a single bulk
   * request may carry (400 above it). It is INDEPENDENT of `sectionCap` and by
   * default smaller (100 vs 300), so any "move every issue" action driven by a
   * rendered section MUST chunk by this number, never by the section size.
   * Optional only so a response from a server that predates the field still
   * types — consumers degrade to one issue per request rather than guessing a
   * default (see `useSprintMutations`).
   */
  bulkMoveCap?: number;
}

/** `GET …/sprints/{id}/completion-preview` — the completion dialog's data source. */
export interface SprintCompletionPreview {
  totalIssueCount: number;
  doneIssueCount: number;
  unfinishedIssueCount: number;
  totalPoints: number | null;
  donePoints: number | null;
  unfinishedPoints: number | null;
  /** Legal "move the unfinished work to →" targets: FUTURE sprints of this project. */
  targetCandidates: SprintRef[];
}

export type UnfinishedDisposition = 'BACKLOG' | 'SPRINT';

/** `POST …/sprints/{id}/complete` — the reported outcome, shown as a summary. */
export interface SprintCompletionResult {
  sprint: Sprint;
  completedIssueCount: number;
  carriedOverIssueCount: number;
  carriedOverToSprintId: string | null;
  donePoints: number | null;
  carriedOverPoints: number | null;
}

export interface Issue {
  id: string;
  number: number;
  key: string;
  title: string;
  description?: string;
  type: IssueType;
  status: Status;
  priority: Priority;
  assignee?: AssigneeInfo;
  reporter: AssigneeInfo;
  parentId?: string;
  // Parent roll-up summary (null/absent when the issue has no parent)
  parentKey?: string;
  parentTitle?: string;
  parentTypeId?: string;
  // Direct-children roll-up (0 when the issue has no children)
  childCount: number;
  doneChildCount: number;
  dueDate?: string;
  // Attached workspace labels, ordered by name (HD-30). The API always sends the
  // key ([] when none); optional here so hand-built fixtures and locally-patched
  // copies stay valid — every consumer reads it as `issue.labels ?? []`.
  labels?: LabelRef[];
  // The project component this issue belongs to (HD-31), or null/absent when it
  // has none. Read as `issue.component ?? undefined` so hand-built fixtures and
  // locally-patched copies stay valid.
  component?: ComponentRef | null;
  // Version links (HD-32), one array per role. The API always sends both keys
  // ([] when none); optional here so hand-built fixtures and locally-patched
  // copies stay valid — every consumer reads them as `issue.fixVersions ?? []`.
  fixVersions?: VersionRef[];
  affectsVersions?: VersionRef[];
  // The sprint this issue is committed to (HD-22), or null/absent when it sits
  // in the ranked backlog. Read as `issue.sprint ?? undefined` so hand-built
  // fixtures and locally-patched copies stay valid.
  sprint?: SprintRef | null;
  // Native estimate (HD-22 §3.4): 0…999 with at most 2 decimals. `null`/absent
  // means UNESTIMATED — deliberately not 0 ("we didn't estimate it" and "it's
  // free" are different statements).
  storyPoints?: number | null;
  // NOTE: `position` (the project-wide backlog/board rank) is deliberately NOT
  // exposed by the API — placement is computed server-side from neighbour
  // anchors, so the client can never invent or corrupt a rank value.
  fields: FieldValueEntry[];          // filled custom fields only
  version: number;
  createdAt: string;
  updatedAt: string;
}

// Board issue-list response (HD-79). The no-`size` board fetch returns the first
// `cap` issues (server-enforced) plus truncation metadata, instead of a bare array.
export interface BoardIssues {
  issues: Issue[];
  truncated: boolean;
  totalAvailable: number;
  cap: number;
}

export interface Comment {
  id: string;
  authorId: string;
  authorName: string;
  body: string;
  createdAt: string;
  updatedAt: string;
}

export interface Attachment {
  id: string;
  filename: string;
  sizeBytes: number;
  contentType: string;
  uploadedById: string;
  uploadedByName: string;
  createdAt: string;
}

export interface IssueHistoryEntry {
  id: string;
  field: string;
  oldValue?: string;
  newValue?: string;
  changedById: string;
  changedByName: string;
  createdAt: string;
}

// ── Admin console (system ADMIN only) ──────────────────────────────────────

export interface UsageInfo {
  workflows: number;
  sets: number;
  projects: number;
  issues: number;
}

// Delegated consoles: a catalog/set row's scope. Rows not matching the current
// console's scope are inherited and shown read-only (GLOBAL/WORKSPACE seen from a
// project console). See pages/admin/AdminApiContext.
export type AdminScopeTag = 'GLOBAL' | 'WORKSPACE' | 'PROJECT';

export interface AdminStatus extends Status {
  archived: boolean;
  usage: UsageInfo;
  scope: AdminScopeTag;
}

export interface AdminPriority extends Priority {
  position: number;
  archived: boolean;
  usage: UsageInfo;
  scope: AdminScopeTag;
}

export interface AdminIssueType extends IssueType {
  archived: boolean;
  usage: UsageInfo;
  scope: AdminScopeTag;
}

export interface AdminWorkflow {
  id: string;
  name: string;
  description?: string;
  systemDefault: boolean;
  statuses: Status[];
  transitions: TransitionRule[];
  projectsUsing: number;
  scope: AdminScopeTag;
}

export interface AdminPrioritySet {
  id: string;
  name: string;
  systemDefault: boolean;
  items: { priority: Priority; isDefault: boolean }[];
  projectsUsing: number;
  scope: AdminScopeTag;
}

export interface AdminField {
  id: string;
  key: string;
  name: string;
  type: FieldType;
  config?: FieldConfig | null;
  description?: string;
  archived: boolean;
  scope: AdminScopeTag;
  // Absent on fields nested inside a set response
  usage: UsageInfo | null;
}

export interface AdminFieldSet {
  id: string;
  name: string;
  systemDefault: boolean;
  items: { field: AdminField; required: boolean; showOnCreate: boolean }[];
  projectsUsing: number;
  scope: AdminScopeTag;
}

export interface AdminIssueTypeSet {
  id: string;
  name: string;
  systemDefault: boolean;
  types: IssueType[];                 // display order
  projectsUsing: number;
  scope: AdminScopeTag;
}

// "Where exactly is this used?" — the expansion behind a usage chip
export interface UsageDetail {
  workflows: string[];
  sets: string[];
  projects: { id: string; key: string; name: string }[];
  issues: number;
}

export interface ProjectBinding {
  projectId: string;
  key: string;
  name: string;
  archived: boolean;
  workspaceId: string;
  workspaceName: string;
  workflowId: string | null;
  prioritySetId: string | null;
  fieldSetId: string | null;
  issueTypeSetId: string | null;
}

// Delegated admin: the sets a project/workspace may bind, per dimension. Each
// option's scope marks whether it's inherited (GLOBAL/WORKSPACE) or the caller's
// own (PROJECT) — see the delegated-admin backend.
export interface SetOption {
  id: string;
  name: string;
  scope: 'GLOBAL' | 'WORKSPACE' | 'PROJECT';
}

export interface BindingOptions {
  workflows: SetOption[];
  prioritySets: SetOption[];
  fieldSets: SetOption[];
  issueTypeSets: SetOption[];
}

// Admin console user directory (system ADMIN only)
export interface AdminUser {
  id: string;
  email: string;
  displayName: string;
  systemRole: 'ADMIN' | 'USER';
  status: 'ACTIVE' | 'PENDING' | 'DISABLED';
  // False while the account still awaits its setup link being used
  hasPassword: boolean;
  createdAt: string;
}

export interface Notification {
  id: string;
  type: string;
  title: string;
  body?: string;
  link?: string;
  read: boolean;
  createdAt: string;
}

export interface WorkspaceMember {
  userId: string;
  email: string;
  displayName: string;
  avatarUrl?: string;
  /**
   * The id of the role this member holds — **the only safe way to identify it**,
   * and what a role picker binds to.
   *
   * A key cannot do this job: `MEMBER` is a built-in key in *both* scopes
   * (workspace Member and project Contributor are two different roles, with two
   * different ids and two different permission sets), so resolving a member's
   * role by key against a catalog spanning both scopes can name the wrong
   * privilege — today, with no custom role involved at all.
   *
   * **Nullable, and it degrades WITH `role`, never past it.** A wrong-scope or
   * foreign row yields `null` here *and* in `role`, deliberately: emitting the id
   * alone would hand the withheld name straight back, since a client would look
   * it up in the catalog and print it. So `roleId === null` means exactly what
   * `role === null` means — this row's role is not nameable; render a
   * placeholder, never a guess.
   */
  roleId: string | null;
  /**
   * The same role's KEY — **display only**. Kept beside `roleId` rather than
   * replaced, so nothing that already renders it breaks; decide with the id.
   */
  role: string | null;
  joinedAt?: string;
}

/** A project membership row (`GET …/projects/{p}/members`); both fields null together, as above. */
export interface ProjectMember {
  userId: string;
  email: string;
  displayName: string;
  avatarUrl?: string;
  roleId: string | null;
  role: string | null;
  joinedAt?: string;
}

// ── Roles & permissions — HD-123 ─────────────────────────────────────────────

/** Which object a role — and every permission inside it — is granted on. */
export type RoleScope = 'WORKSPACE' | 'PROJECT';

/**
 * The delivery capability a permission is *about* — a labelling hint the role
 * editor groups and hides by, **never a check**. Enforcement never consults a
 * capability: a project with `releases` off still accepts and returns version
 * data, so the hiding must never reach a request body.
 */
export type CapabilityHint = 'BOARD' | 'RELEASES';

/** One entry of `GET /api/permissions` — static product metadata, same for every caller. */
export interface PermissionCatalogEntry {
  key: string;
  scope: RoleScope;
  /** Whether this grant may be narrowed to objects the actor owns. */
  supportsOwn: boolean;
  /** Whether it may ONLY ever be granted own-only (`comment.edit` today). */
  ownRequired: boolean;
  capability: CapabilityHint | null;
}

/**
 * A grant in the role editor's OBJECT form — deliberately not `myPermissions`'
 * flat `"comment.edit:own"` wire form. The editor needs the toggle as a field;
 * the suffix encoding exists for the flat client gate. Both are produced from
 * the same server-side grants; do not converge them.
 */
export interface RolePermissionEntry {
  key: string;
  ownOnly: boolean;
}

export interface RoleRef {
  roleId: string;
  name: string;
}

/** A role this one cannot hand out, and the first permission that blocks it. */
export interface RoleBlocker extends RoleRef {
  /** A catalog KEY — the identical string the runtime 403 quotes. */
  missing: string;
}

/**
 * **Which roles this actor may make a default, and why not for the rest**
 * (HD-130, S7) — the compose-time face of the grant ceiling on the two
 * default-role pickers, so an admin sees the bound while choosing rather than as
 * a 403 afterwards.
 *
 * Derived **server-side** with the same predicate the runtime ceiling applies,
 * which is the only reason the greyed-out reason and the refusal quote one
 * string. **Never re-derived in TypeScript**: the own-only/unrestricted
 * asymmetry is subtle, and a second implementation of a server predicate in the
 * SPA is the HD-98/HD-116 bug class by construction.
 *
 * **The two scopes bind differently and are not symmetric.** At workspace scope
 * the comparand is a *constant* — the built-in Contributor's set ∪ the actor's
 * workspace-wide project grants; at project scope it is the actor's real
 * effective set in that project. A role settable at one scope may be refused at
 * the other, so never reuse one block for the other picker.
 */
export interface SettableRolesView {
  /** Roles the ceiling admits, in the catalog's display order. */
  canSet: RoleRef[];
  /** Roles it refuses, each with the FIRST permission that is why. */
  cannotSet: RoleBlocker[];
}

/** A workspace's project-access configuration, on either side of a change. */
export interface ProjectAccessState {
  mode: ProjectAccessMode;
  /** `null` = the built-in Contributor. An id, never a name. */
  defaultProjectRoleId: string | null;
}

/** One live project's row in the impact preview. */
export interface ProjectAccessImpactRow {
  id: string;
  key: string;
  name: string;
  /** ACTIVE workspace members with no explicit row here — the people the mode is about. */
  membersOnDefault: number;
  /** ACTIVE members who do have one, and are therefore unaffected. */
  explicitMembers: number;
  /**
   * Of the members on the default, those whose workspace role grants neither
   * `project.curate.all` nor `project.administer.all` — i.e. who would hold the
   * empty set here afterwards.
   */
  membersLosingEverything: number;
  /** Whether this project would end up with nobody at all holding `issue.create`. */
  noWritersAfter: boolean;
}

/**
 * **What a project-access change would do** (HD-130, S7 §4) — the body of
 * `POST /api/workspaces/{ws}/project-access/preview`, and the `impact` block of
 * `GET …/project-access` computed for the current state.
 *
 * **Counts are advisory; refusals are authoritative.** Every number here has a
 * definition a reviewer can check against a query, but only one of them is a
 * guarantee: `strandedProjects` is re-derived *inside the write's transaction*
 * and enforced there whether or not the caller ever previewed — so a
 * clean-looking preview can still be followed by a 409
 * `STRANDED_BY_INHERITANCE`. The rest describe a population
 * (`workspace_members` × `project_members`) that is not the row being written, so
 * no optimistic check could make them exact. There is deliberately no token, no
 * echo and no `expectedCount`.
 *
 * The consequence for the client: **re-fetch on opening the confirm dialog and
 * never cache a preview across a dialog close.** `computedAt` is the timestamp
 * that makes the staleness admitted rather than hidden.
 *
 * **How many, never who.** Projects are named; people never are.
 */
export interface ProjectAccessImpact {
  /** When this snapshot was taken. ISO-8601. */
  computedAt: string;
  from: ProjectAccessState;
  to: ProjectAccessState;
  activeMembers: number;
  projects: number;
  projectsWithNoExplicitMembers: number;
  /**
   * **The honest headline.** Live projects where, after the change, *nobody at
   * all* holds `issue.create`. Not "only admins can work there": a workspace
   * Owner/Admin holds `project.curate.all` — project.edit, component.manage,
   * version.manage, sprint.manage — and no issue or comment permission, so in a
   * restricted project with no explicit members nobody can file or edit an issue,
   * **including the Owner**. A warning, never a refusal.
   */
  projectsWithNoWriters: number;
  /** One row per live project, in key order. */
  perProject: ProjectAccessImpactRow[];
  /**
   * The projects the write will **409** on — they have administrators only by
   * inheritance and the change takes it away. Empty is the normal answer, and a
   * non-empty list is a block, not a warning: there is no adoption retry.
   */
  strandedProjects: ProjectRef[];
}

/** `GET /api/workspaces/{ws}/project-access` — one request behind the General page. */
export interface ProjectAccessSettings {
  mode: ProjectAccessMode;
  /** `null` = the built-in Contributor. */
  defaultProjectRoleId: string | null;
  settable: SettableRolesView;
  /** The impact of the workspace **as it stands** — not of any proposal. */
  impact: ProjectAccessImpact;
}

/**
 * `GET /api/workspaces/{ws}/projects/{p}/default-role` — what the project's
 * default-access picker opens against.
 *
 * The two ids duplicate `ProjectResponse.defaultRole` on purpose: a picker dialog
 * is worth a self-contained read. `mode` rides along so the dialog can say "this
 * workspace is Restricted, so nothing is inherited right now" without a second
 * fetch — it is read here and **owned by the workspace response**; nothing writes
 * it through this endpoint.
 */
export interface ProjectDefaultRoleSettings {
  projectRoleId: string | null;
  workspaceRoleId: string | null;
  mode: ProjectAccessMode;
  /** The PROJECT-scope ceiling — the actor's real effective set here, nobody exempt. */
  settable: SettableRolesView;
}

/**
 * What a role could hand out — **server-derived**, carried by every role
 * response and answered by `POST …/roles/preview` for a set being composed.
 *
 * Never re-derived in TypeScript: the ceiling is set containment with per-grant
 * width (an unrestricted grant is not covered by an own-only one), and a second
 * implementation of a server predicate in the SPA is the HD-98/HD-116 bug class
 * by construction.
 *
 * **It is a lower bound**, computed from the role alone and ignoring what a real
 * holder additionally gets from their workspace role — so the copy reads "on its
 * own, this role can assign: …".
 */
export interface RoleAssignmentView {
  managesMembers: boolean;
  canAssign: RoleRef[];
  cannotAssign: RoleBlocker[];
  /** Codes, not copy; an unrecognised one is informational. */
  warnings: string[];
}

/** Manages a roster and can hand out nothing that does anything. Warns, never blocks. */
export const MANAGES_MEMBERS_BUT_ASSIGNS_NOTHING = 'MANAGES_MEMBERS_BUT_ASSIGNS_NOTHING';

/**
 * Where a role is in play. Every count is filtered to the asking workspace —
 * built-ins are shared rows, so an unscoped count would publish another tenant's
 * headcount.
 *
 * `inUse` is the exact disjunction the delete guard applies (`members`,
 * `invites`, `projectMembers`, `defaultForProjects`, `defaultForWorkspace` —
 * **not** `projects`, which only counts how many distinct projects the project
 * memberships spread across), published so a client cannot compute a different
 * answer and then be surprised by a 409.
 */
export interface RoleUsage {
  roleId: string;
  members: number;
  invites: number;
  projectMembers: number;
  projects: number;
  defaultForProjects: number;
  defaultForWorkspace: boolean;
  inUse: boolean;
}

export interface Role {
  id: string;
  scope: RoleScope;
  key: string;
  name: string;
  description?: string;
  /** Product metadata shared by every workspace: neither editable nor deletable. */
  builtIn: boolean;
  position: number;
  /** Optimistic-concurrency token — send it back on PATCH. */
  version: number;
  permissions: RolePermissionEntry[];
  assignment: RoleAssignmentView;
  /**
   * `null` when it was not asked for (`includeUsage` absent or false) — which
   * says NOTHING about whether the role is in use. When it *was* asked for every
   * role carries a full object, including one in play nowhere (all zeroes,
   * `inUse: false`). Never read `null` as "unused".
   */
  usage: RoleUsage | null;
}

/** `{id, key, name}` — the shape of both the 409 `projects` extension and `adoptedProjects`. */
export interface ProjectRef {
  id: string;
  key: string;
  name: string;
}

/**
 * `DELETE …/workspaces/{ws}/members/{userId}` answered **200**: it granted the
 * caller a role in the projects it took over. A removal that granted nothing
 * answers 204 and this is absent — branch on the body, never assume 204.
 */
export interface MemberRemovalResult {
  adoptedProjects: ProjectRef[];
}

export interface ApiError {
  title: string;
  detail?: string;
  status: number;
}

// ── Advanced search (HQL) — HD-25 ───────────────────────────────────────────

// One search hit: the full issue (same shape as anywhere else) plus its owning
// project's identity — results are cross-project so each row self-identifies.
export interface SearchResultRow {
  issue: Issue;
  projectId: string;
  projectKey: string;
  projectName: string;
}

// A picklist entry from the schema (STATUS/TYPE/PRIORITY) or /suggest typeahead.
// `value` is the resolvable literal to insert (email for USER); null = insert the
// label (name-resolved picklists).
export interface SearchValueOption {
  label: string;
  value: string | null;
}

// One queryable HQL field's public schema (drives autocomplete).
export interface SearchField {
  name: string;
  // data-type family (serialized as `type`): ENUM_REF/USER_REF/ISSUE_REF/LABEL_REF/
  // TEXT/DATE/TIMESTAMP/NUMBER. LABEL_REF (HD-30) is many-valued — it compiles to
  // an EXISTS over the join table server-side and is not sortable.
  type: 'ENUM_REF' | 'USER_REF' | 'ISSUE_REF' | 'LABEL_REF' | 'TEXT' | 'DATE' | 'TIMESTAMP' | 'NUMBER';
  operators: string[];
  nullable: boolean;
  sortable: boolean;
  // The value-source token (STATUS/TYPE/PRIORITY/USER/DATE/…) or null when no picklist.
  valueSuggest: string | null;
  functions: string[];
}

export interface SearchSchema {
  fields: SearchField[];
  keywords: string[];
  // Value picklists keyed by value-source token (STATUS/TYPE/PRIORITY); USER absent.
  values: Record<string, SearchValueOption[]>;
}

// A saved, workspace-scoped HQL data source (own + shared). `mine` is
// caller-relative — drives whether rename/share/delete affordances are shown.
export interface SavedFilter {
  id: string;
  name: string;
  hql: string;
  shared: boolean;
  ownerId: string;
  ownerName: string;
  mine: boolean;
  createdAt: string;
  updatedAt: string;
}
