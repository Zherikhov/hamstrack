package com.hamstrack.project.entity;

public enum ProjectRole {
    MANAGER, MEMBER, VIEWER;

    public boolean isAtLeast(ProjectRole required) {
        return this.ordinal() <= required.ordinal();
    }
}
