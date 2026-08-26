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

/**
 * `GET …/backlog/sections/backlog` and `GET …/backlog/sections/{sprintId}`
 * (HD-96) — ONE planning section, fetched on its own so a section can be made
 * current in place without refetching the whole view.
 *
 * Field-identical to the aggregate's section records plus the two caps the view
 * carries at its top level, so a refreshed section is patched into a cached
 * `BacklogView` without translating anything. That identity is the point: the
 * defect this endpoint fixes was a refreshed section answering under a different
 * honesty protocol than the rendered one, so `truncated` here means exactly what
 * it means on `BacklogView` — "this section holds more matching issues than
 * `sectionCap`" — and never "more exist than the page you received".
 *
 * The request carries no page and no size (see `apiGetBacklogSection`), so this
 * response is the only place any of these numbers come from.
 */
export interface BacklogSectionResponse extends BacklogSectionBase {
  /** The section's sprint, or **null** for the ranked backlog section. */
  sprint: Sprint | null;
  /** `app.agile.section-max-issues` as this response was built — read, never sent back. */
  sectionCap: number;
  /**
   * `app.agile.max-issues-per-bulk-move`. Required, unlike `BacklogView`'s: this
   * endpoint is newer than the field, so a server that answers here always carries it.
   */
  bulkMoveCap: number;
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
  /**
   * The workspace this notification belongs to. Always present: the row is
   * tenanted, and `link` is a rendering detail whose shape (or absence) varies
   * by the service that raised it — never parse the tenant out of it.
   */
  workspaceId: string;
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
  // What the Insights panel may be OFFERED (HD-140). Optional so a client can
  // still talk to a server that predates the panel — absent means "offer
  // everything", never "offer nothing": withholding a control because an
  // unrelated request was thin is the same mistake as inferring a capability
  // from data.
  insights?: SearchSchemaInsights;
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

// ── Reports (epic HD-5, slice R1) ────────────────────────────────────────────
// Mirrors `com.hamstrack.report.dto`. Reads need project membership and nothing
// else — reports are deliberately NOT permission-gated (reports-proposal §4.2),
// so nothing here has a `myPermissions` twin.

/** Bucket width. Boundaries are UTC; WEEK starts on Monday (PostgreSQL `date_trunc`). */
export type ReportInterval = 'DAY' | 'WEEK';

/**
 * The provenance block every report response carries (§4.3). It exists to be
 * PRINTED, not swallowed: the recurring complaint about competitors' reports is
 * "these numbers don't match what I expected", and its mechanism is a report
 * that quietly left data out.
 */
export interface ReportMeta {
  /** When the server computed the numbers — the reader's anchor across two tabs. */
  computedAt: string;
  /** Distinct issues the numbers were computed from (not the sum of the series). */
  basedOnIssues: number;
  /**
   * Whether `cap` actually bit. Always false on the flow report (it aggregates in
   * SQL, so the row cap physically cannot bite) — but the UI still handles it,
   * because the row-level reports of R3 return the same block and DO truncate.
   */
  truncated: boolean;
  /** The row cap that would bite (`app.reports.max-rows`). */
  cap: number;
  /**
   * When the earliest issue this report could ever have shown was created, or
   * `null` when there is none.
   *
   * This — not `project.createdAt` — is what "we only have N days of history"
   * must be measured from. **It is filtered exactly like the rest of the
   * response**, so with a filter set it is the first issue *of that type /
   * component / label* and the UI must not call it the project's age.
   */
  firstIssueAt: string | null;
  /**
   * Which of the supplied filter parameters (`typeId` / `componentId` /
   * `labelId`) match **no issue in this project at all** — never null, empty
   * when every filter matched something or none was sent.
   *
   * This is the difference between "nothing happened in this window" and "your
   * filter matched nothing", which are the same all-zero picture without it.
   * Note the deliberately weak claim: *no issue in this project carries this
   * id*, NOT "this id does not exist" — a perfectly valid type nobody here has
   * ever used is named too, so the copy may not say the thing was deleted.
   */
  unmatchedFilters: string[];
}

/**
 * One point of the flow series.
 *
 * `resolved` reads as "issues that are closed NOW, dated by their most recent
 * closure": `closed_at` is cleared when an issue leaves a DONE status, so a
 * reopened issue leaves the bucket it used to sit in and joins a new one on
 * reclosure. Past buckets are mutable and the UI is required to say so.
 * `openAtEnd` inherits the same caveat, one integral further on.
 */
export interface FlowBucket {
  /** Bucket START date (a Monday for WEEK). May precede `from` — that bucket is partial. */
  date: string;
  created: number;
  resolved: number;
  openAtEnd: number;
  /**
   * Whether this bucket covers less calendar than a full interval, because the
   * window opens or closes inside it — so its bar is legitimately short.
   *
   * **Authoritative; never re-derived here.** Computing it client-side means
   * re-implementing Monday truncation in a second language, and the first time
   * the two disagree the chart footnotes the wrong bar. Always false at
   * `interval=DAY`, where a day is the unit.
   */
  partial: boolean;
}

export interface FlowTotals {
  created: number;
  resolved: number;
  /** `created - resolved`. Positive = the backlog grew. Computed server-side. */
  net: number;
}

export interface FlowReport {
  /** Echoed back EXACTLY as requested — a too-wide window is a 400, never a clamp. */
  from: string;
  to: string;
  interval: ReportInterval;
  /** Zero-filled and ascending: an empty bucket is present with zeros, never absent. */
  buckets: FlowBucket[];
  totals: FlowTotals;
  meta: ReportMeta;
}

// ── Cycle time, lead time & aging WIP (epic HD-5, slice R3) ──────────────────
// Mirrors `com.hamstrack.report.dto`. Two endpoints, one page: `/cycle-time` is
// windowed and filtered, `/aging` is the CURRENT state and takes no parameters
// at all — a difference the UI has to state out loud, because a reader with a
// type filter set would otherwise read the aging columns as filtered too.

/**
 * Which duration the finished-work half plots. Purely a CLIENT concern: one
 * response carries both measures and both percentile pairs, so the toggle is a
 * re-render, never a refetch — but it still lives in the URL, because a link
 * that loses the measure shows a colleague a different report.
 */
export type CycleMeasure = 'CYCLE' | 'LEAD';

/**
 * One completed issue in the window.
 *
 * `startedAt`/`cycleDays` are **null for an issue that has no recorded start**
 * (`issues.started_at` was backfilled best-effort, so old issues legitimately
 * lack one). Those issues are not plotted in the cycle measure and are counted
 * in `missingStartCount`. We never substitute `createdAt` for a missing start —
 * that turns a cycle-time report into a lead-time report wearing a false name
 * (reports-proposal §2.2).
 */
export interface CycleTimeItem {
  issueId: string;
  key: string;
  title: string;
  typeId: string;
  startedAt: string | null;
  closedAt: string;
  cycleDays: number | null;
  /** Always defined: `created_at → closed_at` exists for every completed issue. */
  leadDays: number;
}

/**
 * A p50/p85 pair, or nothing.
 *
 * Nullable at BOTH levels on purpose: below the 5-issue floor the server
 * suppresses percentiles rather than printing noise (§2.2), and the UI must
 * behave identically whether that suppression arrives as a null pair or as null
 * members. Reading it through one tolerant helper means a shape change on the
 * server can never draw a reference line at `0` days.
 */
export interface Percentiles {
  p50: number | null;
  p85: number | null;
}

/** Both measures' percentiles, computed server-side (`percentile_cont`). */
export interface CycleTimePercentiles {
  cycle: Percentiles | null;
  lead: Percentiles | null;
}

export interface CycleTimeReport {
  /** Echoed back exactly as requested — an over-long window is a 400, not a clamp. */
  from: string;
  to: string;
  /** One row per completed issue in the window; row-capped like every R3 report. */
  items: CycleTimeItem[];
  percentiles: CycleTimePercentiles | null;
  /** Completed issues in the window — the denominator of the honesty sentence. */
  sampleSize: number;
  /**
   * How many of those have **no recorded start**, i.e. no cycle time. Printed,
   * always: *"cycle time available for 812 of 940 completed issues"*. A
   * cycle-time chart that quietly rests on a subset is the exact failure this
   * whole epic is built to avoid.
   */
  missingStartCount: number;
  meta: ReportMeta;
}

/** One open issue in the aging half, aged from `startedAt` (or from creation). */
export interface AgingItem {
  issueId: string;
  key: string;
  title: string;
  ageDays: number;
  assigneeId: string | null;
  /** Null when the issue was never started — it is then aged from its creation. */
  startedAt: string | null;
}

/**
 * One column of the aging half: a non-DONE status of the project's **effective**
 * workflow, in board order.
 *
 * The trailing **"Not on this board"** column — an issue stranded in a status the
 * workflow no longer carries — arrives here too, and is deliberate: those issues
 * must not vanish (§6). It is recognised by a `statusId` the project config does
 * not list (or none at all), never by matching its name.
 */
export interface AgingColumn {
  statusId: string | null;
  name: string;
  category: 'TODO' | 'IN_PROGRESS' | 'DONE' | null;
  items: AgingItem[];
}

export interface AgingReport {
  columns: AgingColumn[];
  /**
   * The completed-work baseline the aging dots are read against. It comes with
   * THIS response, so it does not move when the window above changes — which the
   * page says, rather than letting the reader assume the two halves share a
   * window.
   */
  percentiles: Percentiles | null;
  meta: ReportMeta;
}

// ── Sprint burn-up & sprint review (epic HD-5, slice R4) ─────────────────────
// Mirrors `com.hamstrack.report.dto` (`SprintBurnupResponse` / `SprintReviewResponse`).
// Both endpoints are per-sprint, both read the R2 scope ledger
// (`sprint_scope_events`), and both answer for ANY sprint in ANY project
// regardless of `delivery.board` — a capability gates the UI and never the API
// (delivery-paths §5.1, Rule A). Neither is permission-gated (§4.2).
//
// **Every point number arrives as a `BigDecimal` server-side** and lands here as
// a plain number, already stripped of trailing zeros (`common.util.Points`), so
// `3` and never `3.00`.

/**
 * Issue count (default) or story points — `ReportMeasure` on the server.
 *
 * Unlike {@link CycleMeasure} this one **is on the wire**: the two series are
 * different sums over different rows, so the server computes the one asked for
 * and echoes it back. It therefore joins the query key, and flipping the toggle
 * is a refetch rather than a re-render.
 */
export type SprintMeasure = 'COUNT' | 'POINTS';

/** A membership change. A re-estimate is NEVER one of these (§2.3 rule 1). */
export type ScopeEventType = 'ADDED' | 'REMOVED';

/**
 * One day of the burn-up, `YYYY-MM-DD` in UTC.
 *
 * `scope` is the total work in the sprint that day — it steps up on an add and
 * down on a remove, and never moves on a re-estimate. `completed` is cumulative
 * work closed by the end of that day. Both are zero-filled, never absent.
 *
 * The series ends at **today** (or at `completedAt` for a finished sprint), not
 * at the sprint's planned end: the server simply does not compute days that have
 * not happened. The chart still draws the axis across the whole sprint, with
 * nothing on it after the last real day — see `burnupRows`.
 */
export interface BurnupPoint {
  date: string;
  scope: number;
  completed: number;
}

/**
 * One row of the scope-change log — the ledger, rendered.
 *
 * `issueId` is **null once that issue has been deleted**: the ledger's FK is
 * `ON DELETE SET NULL`, so the row outlives the issue and keeps both of its
 * steps in the scope arithmetic. `key` is the snapshot taken at the event and is
 * always present, which is what lets a deleted issue's line still say which
 * issue it was — so a null `issueId` means "no longer linkable", never "hide me".
 */
export interface ScopeChange {
  /** The ledger's `occurred_at` — when scope MOVED, not when the row was written. */
  at: string;
  issueId: string | null;
  key: string;
  event: ScopeEventType;
  /** In the requested measure: ±1 under COUNT, ±the issue's points under POINTS. */
  delta: number;
  /**
   * Who moved it — **null in two different cases, and neither is an error.**
   *
   * The ordinary one is an account that has since been deleted: the event is a
   * fact about the sprint and outlives the user who caused it. The second is a
   * decision rather than a foreign key — attribution is dropped on the rows of a
   * **deleted issue**, because `issue_history` cascades away with the issue
   * while the ledger row survives, which would otherwise leave this log as the
   * only place in the product still naming who touched a since-deleted issue and
   * when. The design preserves the key and the estimate on purpose; it does not
   * preserve a person. So render a null actor as ordinary, never as a fault.
   */
  actorId: string | null;
  /**
   * The points the issue carried at the moment of the event — the ledger's
   * snapshot, and **independent of `delta`**: `delta` is ±1 under COUNT, while
   * this is what the issue weighed either way. Null when it entered unestimated.
   *
   * This is what closes Rule B (§5.2 — "a value the project already recorded
   * stays visible when a capability is switched off"): a project with
   * `estimation` off is charted in COUNT, so without this column the log would
   * carry no point value at all and estimates the project had already recorded
   * would vanish with the toggle.
   */
  storyPoints: number | null;
}

export interface SprintBurnupReport {
  /**
   * Null only for a parameterless request against a project with no ACTIVE
   * sprint — `SprintRef.of(null)` is null. The SPA never makes that request (it
   * resolves a sprint for its picker first and always names one), but the field
   * is typed honestly so a page cannot dereference it by accident.
   */
  sprint: SprintRef | null;
  /**
   * The sprint's start — the ledger dates the commitment batch to it, not to the
   * click. **Null for a sprint that has never been started**, which is a 200
   * with an empty series: commitment is an event and it has not happened.
   */
  startAt: string | null;
  /** The sprint's planned end; null when it was started without one. */
  endAt: string | null;
  measure: SprintMeasure;
  /** Scope at `startAt` — what the ideal guide is drawn TO (never current scope). */
  committedAtStart: number;
  /**
   * Issues in the sprint with no estimate. They weigh zero in a POINTS series
   * **and are counted here** — never silently zero, which is documented failure
   * mode #4 of the burndown (§1.2, §6). Always present; `0` under COUNT.
   */
  unestimatedCount: number;
  /** One point per UTC day from the sprint's start to today, ascending. */
  series: BurnupPoint[];
  /**
   * Every membership change after the start, ascending by instant then key.
   *
   * Clipped to {@link seriesTruncatedAt} **only when the chart is clipped**, and
   * deliberately not otherwise: a completed sprint's carry-over rows are stamped
   * a moment AFTER `completed_at`, so bounding the log at the last plotted point
   * would drop the completion's own moves — the ones that explain where the
   * remaining scope went.
   */
  scopeChanges: ScopeChange[];
  /**
   * The UTC day the series stops at, or null when the whole sprint is drawn.
   *
   * A sprint's start may be **backdated arbitrarily**, so the day count is
   * caller-influenced and is bounded by `app.reports.max-window-days`, keeping
   * the FIRST days because they carry the commitment. This is its own signal and
   * **not** `meta.truncated`: that flag means the `app.reports.max-rows` cap bit
   * and is printed beside `meta.cap`, so reusing it made a day-clipped
   * twelve-issue sprint answer `truncated: true, cap: 20000` and quote twenty
   * thousand at a report that dropped nothing of the kind. Two limits, two
   * signals; the one that fired names the day it fired on.
   */
  seriesTruncatedAt: string | null;
  meta: ReportMeta;
}

/**
 * One issue row of the sprint review.
 *
 * The rule that shapes it: **a completed sprint's record may not quietly shed
 * rows.** An issue deleted since the sprint ran still appears — from the
 * ledger's snapshot — with `deleted: true`, its key, and the points it carried
 * on entry; everything that lives on the issue itself (`title`, `typeId`,
 * `assigneeId`, `statusId`, `closedAt`) is null. It is rendered as a real row
 * that says the issue is gone, not hidden and not drawn as a broken link.
 */
export interface SprintReviewIssue {
  /** Null once the issue was deleted — the row is a snapshot, not a join. */
  issueId: string | null;
  /** Snapshotted at the event; present on every row, including a deleted one. */
  key: string;
  title: string | null;
  /** Resolved against the project `config`, exactly as every other type badge is. */
  typeId: string | null;
  assigneeId: string | null;
  statusId: string | null;
  /**
   * **What this issue weighed when it entered this sprint** — the ledger's
   * snapshot, not today's estimate, and null when it entered unestimated.
   *
   * This is the one place the two halves of R4 deliberately disagree: the
   * burn-up plots CURRENT points (so a re-estimate moves its whole line), while
   * the review is a retrospective record and a retro asks what was committed.
   */
  points: number | null;
  /**
   * The issue's CURRENT closure stamp, null when it is open — or when the issue
   * is gone. `closed_at` is cleared on reopen, so a reopened issue leaves its
   * old sprint's completed list; the record answers "is it done", and that
   * answer changed.
   */
  closedAt: string | null;
  /** The issue no longer exists. Authoritative — never re-derived from `issueId`. */
  deleted: boolean;
}

/**
 * One of the review's five lists, with its own count and point sum.
 *
 * `points` is **null when nothing in the list was estimated**, empty lists
 * included — never `0`. The distinction is the whole reason it is nullable: "it
 * added up to nothing" is a measurement and "nobody estimated any of it" is the
 * absence of one, and a sum alone renders them identically. A null point sum is
 * therefore a fact to print as "no estimates here", not a number to default.
 */
export interface SprintReviewList {
  count: number;
  points: number | null;
  unestimatedCount: number;
  issues: SprintReviewIssue[];
}

/**
 * The numbers behind the header line, computed server-side so the sentence and
 * the lists cannot drift: *"completed 18 of 23 issues (41 of 55 points) · 5
 * added after start."*
 *
 * The denominator is **`atEndCount`** — completed plus carried over, i.e. what
 * the sprint held when it ended. That is the population the completed list is a
 * subset of, by construction, so the ratio can never exceed one. Committing it
 * to `committedCount` instead compared two different populations: work added
 * after the start can be completed, so "completed 25 of 23" was reachable.
 *
 * The commitment does not disappear — it stays a labelled list with its own
 * count and sum, and the "5 added after start" clause is the disclosure of the
 * drift between the two.
 */
export interface SprintReviewTotals {
  committedCount: number;
  /** Null when nothing committed was estimated — see {@link SprintReviewList}. */
  committedPoints: number | null;
  /** Completed + carried over: what the sprint held at its end. The denominator. */
  atEndCount: number;
  atEndPoints: number | null;
  completedCount: number;
  completedPoints: number | null;
  addedAfterStartCount: number;
}

/**
 * The artefact a team opens at a retrospective (§2.4) — five lists, not a chart.
 *
 * For a COMPLETED sprint this is a permanent, exact record: the ledger is
 * append-only and id-keyed, so it survives every rename and every re-estimate.
 * A sprint that never started answers 200 with five empty lists, not a 404.
 */
export interface SprintReviewReport {
  /** Null under the same condition as {@link SprintBurnupReport.sprint}. */
  sprint: SprintRef | null;
  /** Null for a sprint that never started — then every list is empty. */
  startAt: string | null;
  endAt: string | null;
  completedAt: string | null;
  /** In the sprint at the moment it started. */
  committed: SprintReviewList;
  addedAfterStart: SprintReviewList;
  /** Out of the sprint before it ended — disjoint from completed and carried over. */
  removedBeforeEnd: SprintReviewList;
  completed: SprintReviewList;
  carriedOver: SprintReviewList;
  totals: SprintReviewTotals;
  meta: ReportMeta;
}

/**
 * One completed sprint of the velocity report (R5, §2.5) — **a sprint, never a
 * person.**
 *
 * There is deliberately no assignee, no member list and no per-person split
 * anywhere in this shape, and none may be added: §1.4 is an evidence-backed
 * refusal, not a preference. *"Velocity was never intended to be used to compare
 * two teams"*; when it escapes the team, *"leaders misinterpret higher story
 * point averages to mean one team is more productive"*, which *"harms their
 * estimating process, creates inflated estimates, and demoralizes the team"*. A
 * per-person breakdown is the same failure one level down, and a tooltip is not
 * a smaller version of it.
 *
 * All four figures are in the report's `measure`: issue counts under `COUNT`,
 * story points under `POINTS`.
 */
export interface VelocitySprint {
  sprintId: string;
  /** The sprint's name as it stands today — the bars' only label. */
  name: string;
  /** When it started — the instant `committed` is measured at. */
  startAt: string;
  /**
   * When it was completed. **Never null** — only COMPLETED sprints are sampled —
   * and it is what orders the bars, so chronology is a fact in the payload
   * rather than something a client infers from sprint names.
   */
  completedAt: string;
  /** What it held when it started. Marked as a level on the bar, never as a target. */
  committed: number;
  /** What was closed in it — the bar itself, and the only input to the forecast. */
  completed: number;
  addedAfterStart: number;
  carriedOver: number;
  /**
   * Issues the sprint held at its end that carried **no estimate** — reported
   * under BOTH measures, and an issue count under both (never a point sum,
   * which for unestimated work would be zero by definition).
   *
   * It is the same disclosure the burn-up makes and it matters more here.
   * Under `POINTS` an unestimated issue weighs zero in its bar — documented
   * failure mode #4 (§1.2, §6) — **and the band is computed from those bars**,
   * so `p50`/`p85` are biased low by exactly the work nobody sized. A forecast
   * quietly reading low, in the report whose only purpose is the forecast, is
   * the failure this field exists to prevent.
   *
   * Under `COUNT` every issue is in the bars whether it was sized or not, so
   * the number distorts nothing — it is shown anyway, because it is what a
   * points view of the same sprints would understate, and a reader flipping the
   * measure must not have to discover that on the way past.
   */
  unestimatedCount: number;
}

/**
 * The band — the thing this report exists for (§2.5).
 *
 * It is a **forecast input, not a scoreboard**: a p50 to plan with and a p85 to
 * treat as a stretch, always printed with the sample size behind them. The
 * sample size is not decoration — the epic refuses forecasting everywhere it
 * cannot state one (§2.3 rule 2), and this is the one report that can.
 *
 * **Suppression is a null percentile, not a missing object** (§6): below three
 * completed sprints the server sends this record with `p50`/`p85` null and
 * `sampleSize` still stating what there was, so a client reads
 * `forecast.p50 === null` rather than testing the parent for existence.
 *
 * The client re-checks the threshold anyway rather than trusting the shape,
 * because a band drawn from two sprints is the failure mode this report exists
 * to avoid, not a rounding error.
 */
export interface VelocityForecast {
  /** The median completed — *plan for about this*. Null when suppressed. */
  p50: number | null;
  /** The 85th percentile — *a stretch, never a target*. Null when suppressed. */
  p85: number | null;
  /** Completed sprints behind the percentiles. Printed even when suppressed. */
  sampleSize: number;
}

/**
 * `GET /reports/velocity?sprints=6&measure=COUNT|POINTS` (§2.5, §4.3).
 *
 * **Project-scoped, and there is no aggregate above it.** No workspace endpoint,
 * no multi-project variant, no comparison affordance in the UI — comparing two
 * teams has to be done by hand, and that friction is the design (§1.4).
 *
 * `sprints` carries the last N COMPLETED sprints in **chronological order,
 * oldest first**, so the bars read left to right in time without a client-side
 * sort (N default 6, max 12 — `400` outside 1..12, with the bound named in the
 * detail, never a silent clamp). A project with no completed sprint answers 200
 * with an empty list, not a 404.
 *
 * Like every sprint report it is **not** gated by `board`: a Kanban project's
 * request is answered exactly as a Scrum project's is (delivery-paths Rule A).
 */
export interface VelocityReport {
  measure: SprintMeasure;
  sprints: VelocitySprint[];
  /** Always present; its percentiles are null when the band is suppressed. */
  forecast: VelocityForecast;
  meta: ReportMeta;
}

// ── Search insights (epic HD-5, slice R6 — the dashboard replacement) ────────
// `POST /api/workspaces/{wsId}/search/insights` (§2.6). Workspace-scoped, not
// project-scoped: its dataset is **the HQL query already in the search box**,
// which is what a gadget grid can never have — one global filter by
// construction, so no two numbers on the panel can come from different sets.
//
// Mirrors `com.hamstrack.report.dto.Insights*`. The response is a **cross tab,
// not a tree**: the bars (`slices`), the legend (`segments`) and their
// intersections (`cells`) are three flat lists, because the two are capped
// SEPARATELY and a bar's height is deliberately not the sum of its stacks.

/** The y axis. `NONE` draws no chart — the panel becomes a pure breakdown. */
export type InsightsMeasure = 'COUNT' | 'POINTS' | 'NONE';

/**
 * The x axis (`slice`) and the optional colour dimension (`segment`) — one list
 * serves both, and the only rule is that they must differ (422 otherwise).
 *
 * `ASSIGNEE` is here and is **refused in velocity** (§2.5/§1.4). Not an
 * inconsistency: this is an ad-hoc query a member runs over their own result
 * set, not a published metric with a permanent home in the navigation.
 */
export type InsightsDimension =
  | 'STATUS' | 'TYPE' | 'PRIORITY' | 'ASSIGNEE'
  | 'COMPONENT' | 'LABEL' | 'SPRINT' | 'PROJECT';

/**
 * One bar, or one legend entry — **and the HQL that reproduces exactly it**.
 *
 * `id` is the entity id, or **null for the no-value bucket** (unassigned, no
 * component, no sprint), which is a real answer and not an error.
 *
 * `hql` is the whole click-through, and it is **the server's job, never the
 * client's**. A fragment is emitted only when it returns precisely the issues
 * this bar counted, and is `null` otherwise — which happens for three separate
 * reasons the panel must not paper over:
 *
 *  1. `PROJECT`, which HQL has no vocabulary for at all;
 *  2. a name owned by two visible projects (two "Billing" components), where
 *     `component = "Billing"` would match a WIDER set than the bar;
 *  3. a name deliberately outside HQL name resolution — a COMPLETED sprint, an
 *     archived component or label — where the fragment would 422 on click.
 *
 * Never rebuild it locally from `label`. Getting (2) and (3) right needs the
 * workspace's whole name→id map, and the failure mode of guessing is a click
 * that returns a different set than the bar and looks exactly like success.
 */
export interface InsightsBucket {
  id: string | null;
  label: string;
  hql: string | null;
}

/** One bar. Both measures are always present — the toggle is a re-render. */
export interface InsightsSlice {
  bucket: InsightsBucket;
  count: number;
  /** Sum of story points; unestimated issues weigh zero, and are counted below. */
  points: number | null;
  unestimatedCount: number;
}

/** One (slice, segment) intersection. Ids match `slices[].bucket.id`/`segments[].id`. */
export interface InsightsCellValue {
  sliceId: string | null;
  segmentId: string | null;
  count: number;
  points: number | null;
  unestimatedCount: number;
}

/**
 * `POST /search/insights` (§2.6, §4.3).
 *
 * **The two truncation flags are not `meta.truncated`.** This report never
 * materialises an issue row, so the row cap physically cannot bite (`meta`
 * reports that honestly). What truncates here is *buckets* — the axis at
 * `sliceCap`, the cross tab at `cellCap` — and each has its own flag because a
 * chart missing bars and a chart missing stacks are different sentences.
 *
 * **`sliceMultiValued` is the one that changes what the numbers mean.** An issue
 * with three labels lands in three bars, so on a many-valued dimension the bars
 * sum to more than `meta.basedOnIssues` and the panel has to say so — an
 * unexplained "sum ≠ total" is precisely the "these numbers don't match"
 * complaint this epic's disclosure rules exist to prevent.
 */
export interface InsightsResponse {
  measure: InsightsMeasure;
  slice: InsightsDimension;
  /** Null when no colour dimension was requested. */
  segment: InsightsDimension | null;
  /** The bars, ranked by the requested measure, then by label. */
  slices: InsightsSlice[];
  /** The legend — every segment bucket the shipped cells refer to. */
  segments: InsightsBucket[];
  /** The cross tab. Empty when unsegmented. */
  cells: InsightsCellValue[];
  sliceMultiValued: boolean;
  segmentMultiValued: boolean;
  slicesTruncated: boolean;
  cellsTruncated: boolean;
  sliceCap: number;
  cellCap: number;
  meta: ReportMeta;
}

/**
 * What `/search/schema` says the Insights panel may be OFFERED (`schema.insights`).
 *
 * **Suggestions, not a contract** — the same sentence that governs `fields`.
 * `SPRINT` is dropped when no visible project plans in sprints and `POINTS` when
 * none estimates; what is omitted still RESOLVES, because a capability may never
 * change a status code (delivery-paths Rule A). So the panel hides the control
 * and still sends, and still renders, a shared URL that names one.
 */
export interface SearchSchemaInsights {
  measures: InsightsMeasure[];
  dimensions: InsightsDimension[];
}
