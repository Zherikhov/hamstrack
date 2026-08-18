package com.hamstrack.project.exception;

import com.hamstrack.common.exception.AppException;
import com.hamstrack.project.dto.ProjectRef;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.stream.Collectors;

/**
 * <strong>409</strong> — removing this workspace member would leave one or more projects
 * with nobody able to manage their membership (HD-136). The workspace-wide twin of
 * {@link LastProjectAdminException}, and the reason it exists at all: removing somebody
 * from a workspace deletes every {@code project_members} row they hold in it, so the
 * invariant {@code ProjectService.removeMember} refuses to break one project at a time was
 * freely breakable — for any number of projects at once — through a different endpoint.
 *
 * <p><strong>And the refusal is satisfiable by whoever received it.</strong> Its first
 * remedy — give those projects another administrator — needs {@code project.member.manage}
 * <em>in</em> them, which no workspace-scoped role grants, so on its own this 409 would
 * hand an insider a way to become unremovable (security review H1). The message therefore
 * also names the retry, {@code DELETE …?adoptStrandedProjects=true}, which makes the caller
 * the administrator of each listed project in the same transaction as the removal. See
 * {@code ProjectAdminGuard.adoptAll}.
 *
 * <p><strong>Refuse rather than cascade, and name the projects.</strong> The alternatives
 * were considered and rejected: silently stranding the projects is discovered months later
 * (recovery is a manual {@code UPDATE} — {@code project.member.manage} is not part of the
 * workspace-admin curator bypass, so not even an Owner can repair it through the API), and
 * a bare "some projects would be stranded" message leaves the admin to hunt through every
 * project's member list for the ones that matter. The cost is accepted and visible:
 * offboarding is blocked until each named project gets another administrator, which is a
 * minute of work the admin can see the shape of.
 *
 * <p><strong>Two variants, told apart by {@code errorType}, not by prose.</strong> The
 * ordinary refusal ({@link #STRANDED_PROJECTS}) is cleared by retrying with the flag; the
 * adoption-blocked one ({@link #ADOPTION_BLOCKED}, see {@link #cannotAdopt}) fails
 * identically on that retry and must not be offered as one. They share a status and a
 * {@code projects} list, so without the discriminator a client could only branch on a
 * sentence — see {@link #getErrorType()}.
 *
 * <p>409 rather than 403 for {@code LastWorkspaceOwnerException}'s reason — the caller's
 * permissions are fine; what refuses is a state invariant. And rather than 422, because
 * nothing about the <em>request</em> is unprocessable: the same request succeeds
 * unchanged once the projects have a second administrator.
 *
 * @see com.hamstrack.project.service.ProjectAdminGuard
 */
public class StrandedProjectsException extends AppException {

    /** How many projects the sentence names before it summarises the rest. */
    private static final int NAMED_IN_DETAIL = 3;

    /**
     * The retry that makes this refusal satisfiable, named in the message itself. A guard
     * whose remedy the reader cannot perform is a lock-out, and the reader here is usually
     * a workspace Owner who has no {@code project.member.manage} anywhere — so the way out
     * has to travel with the refusal rather than live in the docs.
     */
    private static final String RETRY_HINT = "adoptStrandedProjects=true";

    /**
     * <strong>{@code errorType: "STRANDED_PROJECTS"}</strong> — the ordinary refusal. The
     * retry named in {@code detail} is available: repeating the request with
     * {@code adoptStrandedProjects=true} will make the caller the administrator of every
     * listed project and complete the removal.
     */
    public static final String STRANDED_PROJECTS = "STRANDED_PROJECTS";

    /**
     * <strong>{@code errorType: "ADOPTION_BLOCKED"}</strong> — the caller already asked to
     * adopt and could not. <strong>Retrying with the flag fails identically</strong>, so a
     * client must not offer that button here; the way out is somebody else's action, named
     * in {@code detail}.
     */
    public static final String ADOPTION_BLOCKED = "ADOPTION_BLOCKED";

    private final List<ProjectRef> projects;
    private final String errorType;

    public StrandedProjectsException(List<ProjectRef> projects) {
        this(projects, detailFor(projects), STRANDED_PROJECTS);
    }

    private StrandedProjectsException(List<ProjectRef> projects, String detail, String errorType) {
        super(detail, HttpStatus.CONFLICT);
        this.projects = List.copyOf(projects);
        this.errorType = errorType;
    }

    /**
     * <strong>The discriminator between this class's two 409s</strong> (review round 4),
     * published as the {@code errorType} ProblemDetail extension — the same name and shape
     * {@code HqlParseException}/{@code HqlSemanticException} already use, so this is the
     * project's existing convention rather than a second one.
     *
     * <p>Both variants carry the same status and the same {@code projects} list and differ
     * only in a human sentence, which left a client with nothing to branch on — while the
     * two demand <em>opposite</em> behaviour: {@link #STRANDED_PROJECTS} is fixed by
     * retrying with {@code adoptStrandedProjects=true}, and {@link #ADOPTION_BLOCKED} will
     * fail exactly the same way on that retry, so offering the button is worse than not
     * rendering one. Telling them apart by parsing {@code detail} is not a contract.
     *
     * <p><strong>A code rather than a boolean like {@code canAdopt}</strong>, deliberately.
     * A boolean answers only the button question, and the screen has to choose <em>copy</em>
     * too — which keys on the identity of the refusal, not on one of its consequences; a
     * third variant (an adoption blocked for some future reason) would need a second
     * boolean and could then contradict the first, whereas a code stays one field. Clients
     * should treat an unrecognised code as "no retry available", which is the safe default
     * and the only one that cannot invent a button that 409s.
     */
    public String getErrorType() {
        return errorType;
    }

    /**
     * The other refusal this exception carries: the caller asked to adopt, and at least one
     * of the projects <strong>cannot</strong> be adopted without narrowing a role they
     * already hold there (HD-136 review round 3).
     *
     * <p>Reachable only with a custom project role that holds something the adoption role
     * does not — a "QA lead" with {@code issue.delete} and no member management. Overwriting
     * it would silently demote the actor in a project they were rescuing, and §11.2 would
     * then refuse to give the missing grants back, since nobody left holds them. Skipping the
     * project instead and proceeding would strand it, which is the outcome this whole class
     * exists to prevent. So the removal is refused, and the person told is the one person who
     * can fix it: the holder of that role.
     *
     * <p>Same 409 and the same {@code projects} extension as the ordinary refusal — a client
     * that only knows how to render "these projects are in the way" stays correct — with a
     * sentence that names the real obstacle instead of offering a retry that would fail again,
     * and {@code errorType: }{@link #ADOPTION_BLOCKED} so a client does not have to read that
     * sentence to know it.
     *
     * <p><strong>The remedy it names has to be one the reader can execute.</strong> The
     * first version said "give that project another administrator by hand instead" — which
     * needs {@code project.member.manage} <em>there</em>, and this branch is only reachable
     * because the reader does not have it (if they did, the project had two administrators
     * and was never stranded). That is the same lock-out shape the whole adoption path
     * exists to delete, reintroduced inside it. What actually works, and what the sentence
     * now says: ask the member being removed — still ACTIVE, still that project's
     * administrator — to appoint a successor first, or let a <em>different workspace
     * administrator</em> run the removal instead. Note which colleague that has to be: not
     * "someone who already administers the project", because the branch is only reachable
     * when the departing member is that project's <strong>single</strong> administrator, so
     * that set is exactly the one just proven empty. It is somebody who holds no row there
     * at all, or a narrower one — they never hit this refusal, because their own role is
     * not wider than the adoption role, so their adoption simply succeeds.
     */
    public static StrandedProjectsException cannotAdopt(List<ProjectRef> projects, String roleName) {
        var named = projects.stream()
                .limit(NAMED_IN_DETAIL)
                .map(p -> p.name() + " (" + p.key() + ")")
                .collect(Collectors.joining(", "));
        var overflow = projects.size() - Math.min(projects.size(), NAMED_IN_DETAIL);
        var one = projects.size() == 1;
        return new StrandedProjectsException(projects,
                "Your own role in " + named + (overflow > 0 ? " and " + overflow + " more" : "")
                + " holds more than \u201C" + roleName + "\u201D does, so taking "
                + (one ? "it" : "them") + " over would take that away from you "
                + "and nobody could give it back. Ask the member you are removing to appoint "
                + "another administrator " + (one ? "there" : "in each")
                + " while they still can, or have another workspace administrator who does "
                + "not already work in " + (one ? "that project" : "those projects")
                + " run the removal instead.",
                ADOPTION_BLOCKED);
    }

    /**
     * <strong>Every</strong> affected project, for the {@code projects} extension of the
     * ProblemDetail body — the machine-readable half the SPA renders as a list of links.
     * The sentence in {@code detail} is capped; this is not.
     */
    public List<ProjectRef> getProjects() {
        return projects;
    }

    private static String detailFor(List<ProjectRef> projects) {
        var named = projects.stream()
                .limit(NAMED_IN_DETAIL)
                .map(p -> p.name() + " (" + p.key() + ")")
                .collect(Collectors.joining(", "));
        var overflow = projects.size() - Math.min(projects.size(), NAMED_IN_DETAIL);
        if (overflow > 0) {
            named += " and " + overflow + " more";
        }
        return "Removing this member would leave "
               + (projects.size() == 1 ? "a project" : projects.size() + " projects")
               + " without an administrator: " + named
               + ". Give each another administrator first, or repeat this request with "
               + RETRY_HINT + " to take them over yourself.";
    }
}
