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
  myRole: 'OWNER' | 'ADMIN' | 'MEMBER';
  createdAt: string;
}

export interface Project {
  id: string;
  workspaceId: string;
  name: string;
  key: string;
  description?: string;
  archived: boolean;
  myRole: 'MANAGER' | 'MEMBER' | 'VIEWER';
  createdAt: string;
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
  role: string;
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
