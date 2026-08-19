package com.hamstrack.workspace.exception;

import com.hamstrack.common.exception.AppException;
import org.springframework.http.HttpStatus;

/**
 * <strong>409</strong> — another role of the same scope in this workspace already uses this
 * display name (case-insensitively), or it is a built-in's name.
 *
 * <p>Names, not keys, because names are the level users actually read: two roles called
 * "QA lead" in one picker is worse than two keys nobody sees. Keys are server-generated and
 * disambiguate themselves with a {@code _2} suffix instead of refusing.
 *
 * <p><strong>Application-enforced, deliberately.</strong> It is a UX guard, the race outcome
 * is cosmetic (a simultaneous double-submit yields two same-named roles), and a partial
 * unique index on {@code LOWER(name)} would need a migration this slice otherwise does not
 * have — and S4 is specified as adding none.
 */
public class RoleNameConflictException extends AppException {

    public RoleNameConflictException(String name) {
        super("A role named \"" + name + "\" already exists in this workspace",
                HttpStatus.CONFLICT);
    }
}
