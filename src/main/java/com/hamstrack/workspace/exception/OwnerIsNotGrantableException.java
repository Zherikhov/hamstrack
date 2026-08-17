package com.hamstrack.workspace.exception;

import com.hamstrack.common.exception.AppException;
import org.springframework.http.HttpStatus;

/**
 * <strong>403</strong> — only an Owner may hand out the built-in <strong>Owner</strong>
 * role, or administer a member who holds it (roles-permissions-proposal §11.2).
 *
 * <p><strong>Why this is a rule of its own and not a permission comparison.</strong> The
 * built-in Owner and Admin are seeded with <em>identical</em> permission sets, deliberately
 * — V13 says it in as many words: "Owner is a guardrail on assignment, not a bigger role".
 * So the set-containment ceiling ({@link WorkspaceGrantCeilingException}) cannot see any
 * difference between them, and without this an Admin could promote themselves to Owner, or
 * demote the last real one. Encoding the difference as a permission would be dishonest —
 * there is no <em>capability</em> an Owner has and an Admin lacks — so it is encoded where
 * it actually lives: assignment.
 *
 * <p>Together with §11.1 (a workspace must keep one Owner) this is what makes ownership a
 * handover rather than something that can be taken.
 */
public class OwnerIsNotGrantableException extends AppException {

    public OwnerIsNotGrantableException() {
        super("Only an Owner can assign the Owner role or administer another Owner",
                HttpStatus.FORBIDDEN);
    }
}
