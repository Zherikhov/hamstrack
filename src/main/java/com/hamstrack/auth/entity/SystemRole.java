package com.hamstrack.auth.entity;

/**
 * Instance-wide role, independent of workspace/project roles. ADMIN maintains
 * the global taxonomy catalog (statuses, priorities, issue types, workflows)
 * via /api/admin/**. Extensible — future roles (SUPPORT, AUDITOR…) are new
 * constants, not schema changes.
 */
public enum SystemRole {
    ADMIN, USER
}
