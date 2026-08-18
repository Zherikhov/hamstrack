package com.hamstrack.workspace.exception;

import com.hamstrack.common.exception.AppException;
import org.springframework.http.HttpStatus;

/**
 * <strong>409 {@code SELF_HELD_ROLE}</strong> — you cannot delete a role you yourself hold.
 *
 * <p><strong>This closes a self-escalation route no ceiling can see.</strong> Delete the
 * custom "QA" role you hold in project P, reassigning to the built-in Project admin, and you
 * are Project admin of P. The grant ceiling cannot catch it: a ceiling is evaluated per
 * assignment, and this is a bulk {@code UPDATE} over every holder at once. The same trick
 * works at workspace scope.
 *
 * <p><strong>Deliberately blunt rather than a per-row ceiling.</strong> One existence query
 * per scope, zero escalation surface, and a remedy the refused person can perform themselves
 * in one call — change your own role first, or ask another administrator to run the delete.
 * The alternative is a ceiling evaluated against N different project contexts, which is more
 * code, more queries and more ways to be subtly wrong. It can be relaxed later without a
 * data change.
 *
 * <p><strong>Editing a role you hold is the same act by another door</strong> (security
 * review round 2). §11.3 drops the <em>definition</em> ceiling at PROJECT scope so that a
 * workspace Admin can duplicate Contributor — but an edit is a grant to everyone already
 * holding the role, evaluated against nobody, so a member of a project carrying a custom
 * role could simply PATCH twenty permissions into it and gain {@code issue.delete},
 * {@code project.archive} and {@code project.taxonomy.manage} in that project. That is the
 * "remove your own row, add it back bigger" route {@code ProjectService.addMember} refuses,
 * reassembled out of one call. {@link #widening()} refuses it with the same verdict and the
 * same remedy — and only when the edit actually <em>widens</em>, so narrowing or renaming a
 * role you hold stays legal.
 */
public class SelfHeldRoleException extends AppException {

    /** @see #getErrorType() */
    public static final String SELF_HELD_ROLE = "SELF_HELD_ROLE";

    public SelfHeldRoleException() {
        super("You hold this role yourself — reassigning it in bulk would change your own "
              + "access. Move yourself to another role first, or ask another administrator "
              + "to delete it", HttpStatus.CONFLICT);
    }

    private SelfHeldRoleException(String message) {
        super(message, HttpStatus.CONFLICT);
    }

    /**
     * The edit half: this PATCH adds grants the role does not have, and the actor is one of
     * the holders it would add them to. Same error type as the delete half on purpose — it
     * is one rule ("you may not widen your own access in bulk") with two doors, and the SPA
     * renders one piece of copy for it.
     */
    public static SelfHeldRoleException widening() {
        return new SelfHeldRoleException(
                "You hold this role yourself, so widening it would grant your own account "
                + "the added permissions. Move yourself to another role first, or ask "
                + "another administrator to make this change");
    }

    /** @see RoleInUseException#getErrorType() */
    public String getErrorType() {
        return SELF_HELD_ROLE;
    }
}
