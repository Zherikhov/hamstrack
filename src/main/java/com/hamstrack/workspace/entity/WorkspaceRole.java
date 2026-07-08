package com.hamstrack.workspace.entity;

public enum WorkspaceRole {
    OWNER, ADMIN, MEMBER;

    public boolean isAtLeast(WorkspaceRole required) {
        return this.ordinal() <= required.ordinal();
    }
}
