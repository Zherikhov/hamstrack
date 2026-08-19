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
    LEAVING_DEFAULT("Removing this member would leave them with the project's default role"),

    /**
     * Making a role this project's <em>default</em> (HD-130, S7 §3.2) — the picker hands it
     * to every workspace member with no explicit {@code project_members} row here, which is
     * nearly everyone (§2.3). There is no target to name, which is exactly why
     * {@code ProjectService.requireGrantable}'s §4 escape does not apply: the escape rests
     * on {@code target != actor}, and a default's target is everyone, the actor included.
     */
    SETTING_DEFAULT("You cannot make this the default role for everyone in this project"),

    /**
     * Replacing a default that is already wider than the actor's own set — the "current end"
     * of the ceiling. Without it, whatever you may not grant you could still <em>strip</em>:
     * a narrow member-manager would be able to narrow a default of Project admin that only
     * somebody wider could put back.
     */
    REPLACING_DEFAULT("You cannot change a default that grants more than you hold");

    private final String phrase;

    GrantCeilingAction(String phrase) {
        this.phrase = phrase;
    }

    public String phrase() {
        return phrase;
    }
}
