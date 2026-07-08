export interface User {
  id: string;
  email: string;
  displayName: string;
  avatarUrl?: string;
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

export type Priority = 'URGENT' | 'HIGH' | 'MEDIUM' | 'LOW' | 'NONE';

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

export interface IssueHistoryEntry {
  id: string;
  field: string;
  oldValue?: string;
  newValue?: string;
  changedById: string;
  changedByName: string;
  createdAt: string;
}

export interface StatusTransition {
  id: string;
  fromStatusId: string;
  fromStatusName: string;
  toStatusId: string;
  toStatusName: string;
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
