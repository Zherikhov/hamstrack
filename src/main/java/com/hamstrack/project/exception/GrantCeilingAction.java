package com.hamstrack.project.exception;

/**
 * What the caller was trying to do when the project grant ceiling (§11.2) refused them.
 *
 * <p>The same rule refuses three different things and one verb cannot describe them:
 * "You cannot grant X" thrown from a {@code DELETE} reads as nonsense to a lead who was
 * removing somebody, and an error a user cannot map onto the action they took is an error
 * they report as a bug.
 */
public enum GrantCeilingAction {

    /** Assigning the role to a member. */
    GRANTING("You cannot grant the role"),

    /** Removing — or otherwise administering — a member who currently holds it. */
    ACTING_ON("You cannot administer a member holding the role"),

    /**
     * The removal would drop the member onto the project's default role (§5.2), so the
     * ceiling bounds what they would be left <em>inheriting</em>, not what they hold.
     */
    LEAVING_DEFAULT("Removing this member would leave them with the project's default role");

    private final String phrase;

    GrantCeilingAction(String phrase) {
        this.phrase = phrase;
    }

    public String phrase() {
        return phrase;
    }
}
