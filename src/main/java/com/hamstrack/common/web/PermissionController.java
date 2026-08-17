package com.hamstrack.common.web;

import com.hamstrack.common.security.Permission;
import com.hamstrack.common.security.PermissionCatalogEntry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * {@code GET /api/permissions} — the permission catalog
 * (roles-permissions-proposal §4, §6).
 *
 * <p><strong>Global, static product metadata, not tenant data</strong>: the 29 keys are
 * the same in every workspace and every install, so this endpoint is deliberately not
 * workspace-scoped and needs no membership check. It is nonetheless
 * <em>authenticated</em> — it falls under {@code SecurityConfig}'s
 * {@code /api/** → authenticated()} rule, which is the right level: knowing which
 * permissions the product defines tells an anonymous caller nothing useful, but there is
 * also no reason to publish it next to {@code /api/meta}.
 *
 * <p>The response is generated from the {@link Permission} enum on every call, so the
 * catalog a client renders in the role editor and the catalog the server enforces are the
 * same list by construction. Nothing here reads the database.
 */
@RestController
public class PermissionController {

    @GetMapping("/api/permissions")
    public List<PermissionCatalogEntry> catalog() {
        return Permission.catalog().stream().map(PermissionCatalogEntry::of).toList();
    }
}
