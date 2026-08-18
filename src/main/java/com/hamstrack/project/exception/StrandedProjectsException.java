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
 * <p><strong>Several variants, told apart by {@code errorType}, not by prose.</strong> They
 * all share a status and a {@code projects} list and differ in what the reader must do next,
 * which is precisely what a client cannot recover from a sentence: {@link #STRANDED_PROJECTS}
 * is cleared by retrying with the flag; {@link #ADOPTION_BLOCKED} fails identically on that
 * retry and needs a named third party; {@link #ADOPTION_ROLE_UNREADABLE} needs an operator;
 * {@link #LAST_PROJECT_ADMIN_BULK} is fixed by choosing a different change. One code per
 * distinct <em>next action</em> is the rule — see {@link #getErrorType()}.
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
     * adopt and could not, because their <em>own</em> role in a listed project grants more
     * than the adoption role does. <strong>Retrying with the flag fails identically</strong>,
     * so a client must not offer that button here; the way out is somebody else's action,
     * named in {@code detail}.
     */
    public static final String ADOPTION_BLOCKED = "ADOPTION_BLOCKED";

    /**
     * <strong>{@code errorType: "ADOPTION_ROLE_UNREADABLE"}</strong> — the adoption could not
     * proceed because a stored {@code project_members.role_id} in a listed project failed the
     * scope+ownership assertion, so the server will not overwrite a row it cannot read
     * (HD-127 review round 4).
     *
     * <p><strong>Split out of {@link #ADOPTION_BLOCKED} because the two need different
     * handling and the shared copy was false on this branch.</strong> That copy asserts
     * "your own role holds more than the adoption role does" and prescribes asking the
     * departing member to appoint a successor. On this branch both halves are wrong: the
     * actor's role is not the obstacle, and the prescribed retry blocks on the same corrupt
     * row every time, so the reader is sent round a loop they cannot leave. This is the third
     * refusal in this class to be caught naming a cause or a remedy it could not deliver —
     * the rule the three produce is: <em>a refusal may only state what the code actually
     * tested, and may only prescribe an action its reader can perform.</em>
     *
     * <p>No retry, and no action by the actor or by the departing member clears it. It is a
     * data-integrity fault: {@code WorkspaceAccessService.requireRole} has already logged an
     * ERROR and incremented {@code hamstrack.role.scope_violation} before this fires, so the
     * repair is an operator's, on the row those tell them about.
     */
    public static final String ADOPTION_ROLE_UNREADABLE = "ADOPTION_ROLE_UNREADABLE";

    /**
     * <strong>{@code errorType: "LAST_PROJECT_ADMIN_BULK"}</strong> — a role <em>edit</em>
     * or a <em>delete-with-reassign</em> would demote every administrator of the listed
     * projects at once (HD-127 §6, doors 4 and 5). <strong>No retry flag applies</strong>:
     * there is deliberately no adoption path for the bulk doors, because unlike a removal
     * the remedy here is satisfiable by the person being refused — keep the permission, pick
     * a replacement role that also manages members, or fix those projects first. That is the
     * H1 test, met without granting anybody anything.
     */
    public static final String LAST_PROJECT_ADMIN_BULK = "LAST_PROJECT_ADMIN_BULK";

    /**
     * The bulk refusal (HD-127 §6). Same 409, same {@code projects} extension and the same
     * {@code errorType} field as the two removal variants, so the SPA renders one list
     * component for all three — with a sentence that names the actual change rather than a
     * removal, and a code that tells a client not to offer the adoption button.
     *
     * <p><strong>The remedy is per door and must stay so.</strong> It used to be one shared
     * sentence — "choose a role that also grants {@code project.member.manage}" — which is
     * meaningless on the <em>edit</em> door (there is no role to choose; the fix is to keep
     * the permission) and, since round 2, incomplete on the <em>delete</em> door: a reassign
     * target must now also be no wider than the role being deleted, so a remedy naming only
     * the one constraint sends the caller into a 403. A refusal whose stated remedy does not
     * work is worse than one with no remedy at all.
     *
     * @param change what the caller was doing, in the second person — "Removing
     *        {@code project.member.manage} from this role" / "Reassigning this role to
     *        &lt;name&gt;" — so the message reads as one sentence with the list
     * @param remedy what will actually make this succeed, for <em>this</em> door
     */
    public static StrandedProjectsException bulkRoleChange(List<ProjectRef> projects,
                                                           String change, String remedy) {
        return new StrandedProjectsException(projects,
                change + " would leave "
                + (projects.size() == 1 ? "a project" : projects.size() + " projects")
                + " without an administrator: " + named(projects)
                + ". " + remedy,
                LAST_PROJECT_ADMIN_BULK);
    }

    /**
     * The capped project list every {@code detail} in this class opens with — "Apollo (APL),
     * Borealis (BOR) and 4 more". One copy: it existed three times and was about to exist
     * four, and the {@code projects} extension is the uncapped answer anyway.
     */
    private static String named(List<ProjectRef> projects) {
        var named = projects.stream()
                .limit(NAMED_IN_DETAIL)
                .map(p -> p.name() + " (" + p.key() + ")")
                .collect(Collectors.joining(", "));
        var overflow = projects.size() - Math.min(projects.size(), NAMED_IN_DETAIL);
        return overflow > 0 ? named + " and " + overflow + " more" : named;
    }

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
     * <strong>The discriminator between this class's 409s</strong> (review round 4),
     * published as the {@code errorType} ProblemDetail extension — the same name and shape
     * {@code HqlParseException}/{@code HqlSemanticException} already use, so this is the
     * project's existing convention rather than a second one.
     *
     * <p>Every variant carries the same status and the same {@code projects} list and differs
     * only in a human sentence, which left a client with nothing to branch on — while they
     * demand <em>opposite</em> behaviour: {@link #STRANDED_PROJECTS} is fixed by retrying
     * with {@code adoptStrandedProjects=true}, and {@link #ADOPTION_BLOCKED} and
     * {@link #ADOPTION_ROLE_UNREADABLE} will fail exactly the same way on that retry, so
     * offering the button is worse than not rendering one. Telling them apart by parsing
     * {@code detail} is not a contract.
     *
     * <p><strong>A new code per distinct next action, not per distinct sentence.</strong>
     * {@code ADOPTION_ROLE_UNREADABLE} was split off {@code ADOPTION_BLOCKED} (round 4)
     * because its reader must escalate to an operator where the other's must ask a named
     * colleague — and while the two shared a code they also shared copy, which was therefore
     * wrong on one of them. Conversely, do not mint a code for a refusal that ends in the
     * same action: it costs every client a branch and buys nothing.
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
        var one = projects.size() == 1;
        return new StrandedProjectsException(projects,
                "Your own role in " + named(projects)
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
     * The adoption's other stop: a listed project holds a membership row for the caller whose
     * stored {@code role_id} does not pass the scope+ownership assertion, so overwriting it
     * would be overwriting something the server cannot read (HD-127 review round 4).
     *
     * <p><strong>Separated from {@link #cannotAdopt} because that copy is false here</strong>,
     * in both halves: it asserts the caller's role is wider than the adoption role \u2014 untested
     * on this branch, and unknowable, since the row is precisely what could not be read \u2014 and
     * it prescribes asking the departing member to appoint a successor, which cannot clear a
     * refusal that does not depend on who administers the project.
     *
     * <p>So the sentence claims only what was tested and prescribes only what works. It does
     * not name a cause (a mis-scoped role, a foreign one, a row from a restore or a
     * hand-written {@code UPDATE} \u2014 this code cannot tell which), and it does not offer the
     * caller or the departing member an action, because neither has one. See
     * {@link #ADOPTION_ROLE_UNREADABLE}.
     */
    public static StrandedProjectsException adoptionRoleUnreadable(List<ProjectRef> projects) {
        var one = projects.size() == 1;
        return new StrandedProjectsException(projects,
                "Your membership in " + named(projects) + " refers to a role that cannot be "
                + "read, so " + (one ? "that project" : "those projects")
                + " cannot be taken over automatically and the removal has been left "
                + "untouched. This is a problem with stored data rather than with your "
                + "request: retrying will fail the same way, and nothing you or the member "
                + "you are removing can change will clear it. Ask a system administrator to "
                + "repair the membership \u2014 the server has logged what is wrong with it.",
                ADOPTION_ROLE_UNREADABLE);
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
        return "Removing this member would leave "
               + (projects.size() == 1 ? "a project" : projects.size() + " projects")
               + " without an administrator: " + named(projects)
               + ". Give each another administrator first, or repeat this request with "
               + RETRY_HINT + " to take them over yourself.";
    }
}
