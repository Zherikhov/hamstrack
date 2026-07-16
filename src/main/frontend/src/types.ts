export interface User {
  id: string;
  email: string;
  displayName: string;
  avatarUrl?: string;
  // Instance-wide role; ADMIN unlocks /admin (server enforces regardless)
  systemRole?: 'ADMIN' | 'USER';
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
  dueDate?: string;
  fields: FieldValueEntry[];          // filled custom fields only
  version: number;
  createdAt: string;
  updatedAt: string;
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

export interface AdminStatus extends Status {
  archived: boolean;
  usage: UsageInfo;
}

export interface AdminPriority extends Priority {
  position: number;
  archived: boolean;
  usage: UsageInfo;
}

export interface AdminIssueType extends IssueType {
  archived: boolean;
  usage: UsageInfo;
}

export interface AdminWorkflow {
  id: string;
  name: string;
  description?: string;
  systemDefault: boolean;
  statuses: Status[];
  transitions: TransitionRule[];
  projectsUsing: number;
}

export interface AdminPrioritySet {
  id: string;
  name: string;
  systemDefault: boolean;
  items: { priority: Priority; isDefault: boolean }[];
  projectsUsing: number;
}

export interface AdminField {
  id: string;
  key: string;
  name: string;
  type: FieldType;
  config?: FieldConfig | null;
  description?: string;
  archived: boolean;
  // Absent on fields nested inside a set response
  usage: UsageInfo | null;
}

export interface AdminFieldSet {
  id: string;
  name: string;
  systemDefault: boolean;
  items: { field: AdminField; required: boolean; showOnCreate: boolean }[];
  projectsUsing: number;
}

export interface AdminIssueTypeSet {
  id: string;
  name: string;
  systemDefault: boolean;
  types: IssueType[];                 // display order
  projectsUsing: number;
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
