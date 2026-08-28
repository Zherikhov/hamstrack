package com.hamstrack.workspace.controller;

import com.hamstrack.auth.entity.User;
import com.hamstrack.workspace.dto.*;
import com.hamstrack.workspace.service.ProjectAccessService;
import com.hamstrack.workspace.service.WorkspaceMemberService;
import com.hamstrack.workspace.service.WorkspaceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Workspace management: create/list/get workspaces, list members, invite by
 * email and accept invites, since HD-132 administer an existing membership
 * (change a role, remove a member), and since HD-158 see and withdraw the
 * invitations this workspace has issued and not had accepted. The workspace is the tenant
 * boundary — every nested resource is resolved through the caller's membership,
 * and a non-member gets 404 (never 403) so workspace existence is not revealed.
 * Creating a workspace makes the caller OWNER; taxonomy is the global catalog
 * (since M1), so workspace creation no longer seeds any issue types or statuses.
 *
 * <p><strong>Inviting is throttled AFTER tenancy and authorization</strong> (HD-190), unlike the
 * report and search budgets, which are interceptors spent before anything is resolved.
 * {@code POST /{id}/invites} answers 429 + {@code Retry-After} past any of the invitation
 * ceilings ({@code InviteThrottle} — a per-(sender, address) cooldown, a per-address daily
 * cap, and a per-sender hourly/daily volume budget), and a refusal sends no mail and writes
 * no invite. Unlike the report and search budgets, which are interceptors spent before
 * anything is resolved, these run inside the service <em>after</em> tenancy and
 * authorization: an unknown workspace and a non-member alike still answer 404 even when the
 * caller is over every ceiling. Inverting that would answer a recipient-keyed question to a
 * non-member — a 429 where this project requires a 404.
 *
 * <p><strong>One standing invitation per address, and it is refused ABOVE the ceilings</strong>
 * (HD-133). {@code POST /{id}/invites} answers <strong>409 {@code DUPLICATE_INVITE}</strong> when an
 * unaccepted invitation to the same address already exists in this workspace — including an
 * <em>expired</em> one, because {@code workspace_invites_pending_email_uk} is
 * {@code WHERE accepted_at IS NULL} and an index predicate cannot depend on the clock. The remedy
 * the refusal names is a withdrawal, which is why it is only a performable refusal since HD-158.
 * Two different 409s share this endpoint and are told apart by {@code errorType}, not by status:
 * already-a-member carries none and wins.
 *
 * <p>That check sits <em>above</em> {@code inviteThrottle.requireRecipientCeilings}, so a duplicate
 * spends no <em>recipient</em> allowance — and, more importantly, a violation raised BELOW it would
 * roll back the recorded send event and hand callers a free probe of another tenant's ceilings. It
 * sits <em>below</em> {@code inviteThrottle.requireSenderVolume}, so the duplicate 409 still costs
 * the caller a unit of their own hourly and daily volume: an endpoint with no principal throttle
 * interceptor cannot afford a refusal that is free to repeat. Both 429s are therefore possible on
 * this endpoint and they mean different things — over your own volume, versus this recipient has
 * had enough mail — and both are only ever seen by a proven member.
 *
 * <p><strong>Withdrawing an invitation frees nothing measured over time</strong> (HD-158 §5).
 * {@code DELETE /{id}/invites/{inviteId}} deletes the row and touches no
 * {@code mail_send_events} row, so the invite cooldown and the per-inbox daily cap survive it
 * intact and {@code invite → revoke → invite} is refused exactly as {@code invite → invite}
 * would be. The rule this instance of it obeys, stated as a property because a list of paths
 * goes stale one path before it does: <em>a revocation may free only a resource whose count it
 * actually reduces — outstanding rows, a uniqueness slot, a stock cap — and never one measured
 * over time. Deleting the record of an offer does not delete the record of a delivery.</em>
 *
 * <p><strong>Member administration</strong> ({@code PATCH}/{@code DELETE
 * /{id}/members/{userId}}) requires {@code workspace.member.manage} — the same
 * permission the invite path checks (HD-126, S3). Two further rules apply to both verbs:
 * the <em>grant ceiling</em> (§11.2 — nobody may hand out, or act on a member holding, a
 * role that grants something they do not hold themselves, plus the built-in Owner
 * guardrail) and the <em>last-Owner</em> guard (409 — a workspace must never lose its last
 * owner, including when an owner acts on themselves).
 *
 * <p>{@code DELETE} carries one guard the {@code PATCH} cannot need: it deletes the
 * member's {@code project_members} rows, so it is refused with a <strong>409 naming every
 * affected project</strong> ({@code projects} extension, HD-136) when it would leave a
 * project with no holder of {@code project.member.manage}. Checked after the last-Owner
 * and self-removal guards, so at most one refusal is reported.
 *
 * <p>That 409 is <strong>satisfiable by the caller who received it</strong>, which is what
 * {@code ?adoptStrandedProjects=true} is for: repeating the DELETE with it makes the
 * <em>caller</em> an administrator of each named project inside the same transaction and
 * then removes the member. It has to exist, because the refusal’s own remedy — give those
 * projects another administrator — needs {@code project.member.manage} <em>in</em> them,
 * which no workspace-scoped role grants; without it an insider could make themselves
 * unremovable. The flag names no projects on purpose: the set adopted is the one the server
 * recomputes under lock in that transaction, so a client cannot widen it, and the response is
 * <strong>200 with the list of what it granted</strong> rather than a silent 204. What it
 * grants is deliberately narrow — the built-in <em>Team lead</em> (Contributor plus
 * {@code project.member.manage}), never Project admin: enough to appoint a real
 * administrator and to keep working in the project, with no authority over its settings, its
 * archive state, its taxonomy, or anything irreversible ({@code issue.delete}, unrestricted
 * {@code attachment.delete}).
 *
 * <p>Two narrowings decide what "without an administrator" means here, and both can change
 * the answer a caller sees: only <strong>ACTIVE</strong> accounts count (a deactivated sole
 * administrator does not hold the project, so removing them is not refused — and a
 * deactivated co-administrator does not save one), and <strong>archived</strong> projects
 * are excluded entirely, since a frozen project must never block an offboarding. The
 * project-scoped {@code DELETE /projects/{p}/members/{u}} deliberately does NOT share that
 * archived exclusion — see {@code ProjectAdminGuard}.
 */
@RestController
@RequestMapping("/api/workspaces")
@RequiredArgsConstructor
public class WorkspaceController {

    private final WorkspaceService workspaceService;
    /** HD-132: administering an EXISTING membership (role change / removal). */
    private final WorkspaceMemberService workspaceMemberService;
    /** HD-130 (S7): the project-access mode and the workspace default-role picker. */
    private final ProjectAccessService projectAccessService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WorkspaceResponse create(@AuthenticationPrincipal User user,
                                    @Valid @RequestBody CreateWorkspaceRequest req) {
        return workspaceService.create(user, req);
    }

    @GetMapping
    public List<WorkspaceResponse> list(@AuthenticationPrincipal User user) {
        return workspaceService.listForUser(user);
    }

    @GetMapping("/{id}")
    public WorkspaceResponse get(@AuthenticationPrincipal User user, @PathVariable UUID id) {
        return workspaceService.get(user, id);
    }

    @GetMapping("/{id}/members")
    public List<WorkspaceMemberResponse> members(@AuthenticationPrincipal User user,
                                                 @PathVariable UUID id) {
        return workspaceService.listMembers(user, id);
    }

    /**
     * <strong>Workspace settings → General</strong> (HD-130, S7 §7.1 W3): rename the
     * workspace, switch its project-access mode, and choose the default project role every
     * member inherits where they have no explicit {@code project_members} row.
     *
     * <p>Gate: {@code workspace.edit}. Every field is optional and independent — a body naming
     * only {@code name} does not disturb the mode, and vice versa.
     *
     * <p><strong>200</strong>, including for a request whose values already hold (the row is
     * then not written at all, so {@code updated_at} does not move) · <strong>400</strong> an
     * empty body, or an unknown {@code projectAccessMode} · <strong>403</strong> missing
     * {@code workspace.edit}, or the grant ceiling on the default role — naming the permission
     * the actor lacks; the built-in workspace Owner is exempt in their own workspace ·
     * <strong>404</strong> unknown workspace or non-member, indistinguishably ·
     * <strong>409 {@code STRANDED_BY_INHERITANCE}</strong> when the change would leave projects
     * whose administrators exist only through the default with nobody able to manage their
     * membership (doors 7 and 8; the {@code projects} extension names them, and there is
     * deliberately no adoption retry) · <strong>422</strong> a {@code defaultProjectRoleId}
     * that is unknown, foreign or WORKSPACE-scoped, or a body sending both
     * {@code defaultProjectRoleId} and {@code clearDefaultProjectRole}.
     */
    @PatchMapping("/{id}")
    public WorkspaceResponse update(@AuthenticationPrincipal User user,
                                    @PathVariable UUID id,
                                    @Valid @RequestBody UpdateWorkspaceRequest req) {
        return projectAccessService.update(user, id, req);
    }

    /**
     * The General page's single read (S7 §7.1 W1): mode, declared default project role, which
     * roles this actor may set it to (with the first missing permission for each one they may
     * not), and the impact of the workspace as it stands.
     *
     * <p>Gate: {@code workspace.edit} — it is the control's own preview, and it aggregates
     * write access workspace-wide, which is a different object from the project member lists
     * that are open to every member. <strong>200</strong> · <strong>403</strong> ·
     * <strong>404</strong>.
     */
    @GetMapping("/{id}/project-access")
    public ProjectAccessResponse projectAccess(@AuthenticationPrincipal User user,
                                               @PathVariable UUID id) {
        return projectAccessService.get(user, id);
    }

    /**
     * <strong>What would this change do?</strong> (S7 §7.1 W2) — the same body as the
     * {@code PATCH}, running the same guards and <strong>persisting nothing</strong>. POST
     * because it carries a body, exactly as {@code POST /roles/preview} does.
     *
     * <p>The counts are advisory — they describe a population
     * ({@code workspace_members} × {@code project_members}) that is not the row being written,
     * so they carry {@code computedAt} and no token, echo or {@code expectedCount}. The one
     * number that must be exact, {@code strandedProjects}, is re-derived under the write and
     * enforced there whether or not the caller ever previewed.
     *
     * <p>A ceiling failure surfaces here as the ordinary <strong>403</strong> and never as a
     * "would fail" field in a 200 body: a preview that succeeds while describing a refusal
     * teaches a client to ignore it. Same 400/403/404/422 as the {@code PATCH}; no 409 — the
     * stranding it would produce is a field, because that is the question being asked.
     */
    @PostMapping("/{id}/project-access/preview")
    public ProjectAccessImpactResponse previewProjectAccess(
            @AuthenticationPrincipal User user,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateWorkspaceRequest req) {
        return projectAccessService.preview(user, id, req);
    }

    /**
     * Change an existing member's workspace role (HD-132).
     *
     * <p>200 · <strong>403</strong> caller is a member but below ADMIN, or the edit breaks
     * the grant ceiling (nobody may act on — or hand out — a role stronger than their own)
     * · <strong>404</strong> unknown workspace, caller not a member, or the target holds no
     * membership <em>here</em> (which says nothing about whether the account exists) ·
     * <strong>409</strong> it would demote the workspace's last owner.
     */
    @PatchMapping("/{id}/members/{userId}")
    public WorkspaceMemberResponse updateMember(@AuthenticationPrincipal User user,
                                                @PathVariable UUID id,
                                                @PathVariable UUID userId,
                                                @Valid @RequestBody UpdateWorkspaceMemberRequest req) {
        return workspaceMemberService.updateRole(user, id, userId, req);
    }

    /**
     * Remove a member from the workspace (HD-132) — their {@code workspace_members} row and
     * their {@code project_members} rows in this workspace, plus the one reference that must
     * not outlive access: the issue <em>assignee</em>, which is a statement about who is
     * responsible now. The account itself is global and is untouched, as is every piece of
     * historical attribution, their saved filters, and any component they lead (HD-31 §5.4
     * keeps a departed lead and skips them at auto-assign time).
     *
     * <p>Removal also revokes the ways back in: every unaccepted invite for that address in
     * this workspace is deleted (a leftover one would otherwise re-appear as a live join
     * button the moment the membership row is gone), and their open SSE streams are closed
     * once the transaction commits.
     *
     * <p><strong>{@code 204}</strong> for an ordinary removal; <strong>{@code 200}</strong>
     * with {@code {"adoptedProjects":[…]}} when {@code adoptStrandedProjects=true} actually
     * granted the caller a role somewhere. The asymmetry is deliberate: an adoption is the
     * one thing this endpoint <em>grants</em>, and a 204 would leave the actor to discover
     * it from a log line they cannot read (HD-136).
     *
     * <p>403/404 exactly as {@code PATCH} above. <strong>409</strong> covers four
     * different states, and only two of them carry an {@code errorType}:
     * {@code STRANDED_PROJECTS} — the removal would leave the listed projects with no
     * administrator, so retry with {@code adoptStrandedProjects=true} to take them over —
     * and {@code ADOPTION_BLOCKED}, where that retry has already been tried and cannot
     * work. The other two carry no extension at all and are told apart by
     * {@code Retry-After}: a lost row-lock race has it, and means retry the identical
     * request; the workspace's last owner does not, and means change something first
     * (promote another owner).
     * <strong>422</strong> when the target is the caller: self-removal ("leave workspace")
     * is a different feature with its own UX and is not built yet, so this endpoint refuses
     * rather than quietly doing it. A sole owner deleting themselves gets the 409 instead —
     * "promote another owner first" is the answer that will still be true once leaving
     * exists. A second DELETE for an already-removed member is a clean 404, not a 500.
     */
    @DeleteMapping("/{id}/members/{userId}")
    public ResponseEntity<MemberRemovalResponse> removeMember(
            @AuthenticationPrincipal User user,
            @PathVariable UUID id,
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "false") boolean adoptStrandedProjects) {
        var adopted = workspaceMemberService.remove(user, id, userId, adoptStrandedProjects);
        return adopted.isEmpty()
                ? ResponseEntity.noContent().build()
                : ResponseEntity.ok(new MemberRemovalResponse(adopted));
    }

    @PostMapping("/{id}/invites")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> invite(@AuthenticationPrincipal User user,
                                      @PathVariable UUID id,
                                      @Valid @RequestBody InviteMemberRequest req) {
        workspaceService.inviteMember(user, id, req);
        return Map.of("message", "Invite sent to " + req.email());
    }

    /**
     * <strong>Every invitation this workspace has issued and not had accepted</strong>
     * (HD-158 §4.1) — the administrator's view, and the counterpart to
     * {@code GET /api/invites}, which is the <em>invitee's</em>.
     *
     * <p>Gate: {@code workspace.member.manage}. <strong>200</strong> a JSON array, newest
     * first, no envelope and no pagination — consistent with {@code GET /{id}/members},
     * which is unbounded over the same population · <strong>403</strong> a proven member
     * without the permission, naming it · <strong>404</strong> unknown workspace or
     * non-member, indistinguishably.
     *
     * <p>Two properties of the array worth stating on the wire contract, because a client
     * would otherwise guess wrong about both. <strong>Expired rows are included</strong>,
     * carrying {@code status: "EXPIRED"} — nothing in this product sweeps one, a member
     * removal deletes one, and HD-133's uniqueness will refuse a re-invite over one, so a
     * row hidden here is a row no admin can clear and no future refusal can point at.
     * <strong>Accepted rows are excluded</strong> — they are history, the membership row is
     * the live fact, and a withdraw control beside something withdrawal cannot affect is a
     * lie.
     *
     * <p>{@code role} and {@code roleId} are {@code null} <em>together</em> on a row whose
     * {@code role_id} fails the scope/ownership assertion; the rest of the list is
     * unaffected. Emitting the id of a role whose key was withheld would hand the name back
     * by proxy.
     */
    @GetMapping("/{id}/invites")
    public List<WorkspaceInviteResponse> invites(@AuthenticationPrincipal User user,
                                                 @PathVariable UUID id) {
        return workspaceService.listInvites(user, id);
    }

    /**
     * <strong>Withdraw an invitation</strong> (HD-158 §4.2) — a hard delete, so the emailed
     * token link and {@code POST /api/invites/{id}/accept} both answer 404 immediately
     * afterwards and the invitee's own list stops showing it, with nothing else written.
     *
     * <p>Gate: {@code workspace.member.manage}, and deliberately <em>not</em> the grant
     * ceiling — withdrawing is subtraction and grants the revoker nothing, so anyone who may
     * administer membership may withdraw any invitation here, including ones they did not
     * send.
     *
     * <p><strong>204</strong> withdrawn, including for an expired invitation (the list offers
     * the control on every row it shows, so it works on every row it shows) ·
     * <strong>403</strong> · <strong>404</strong> unknown workspace, non-member, an id
     * belonging to another workspace, or <strong>an invitation already withdrawn</strong> ·
     * <strong>409 {@code INVITE_ALREADY_ACCEPTED}</strong> the invitee accepted it in the
     * meantime.
     *
     * <p><strong>The 404 and the 409 are different states with different remedies, and a
     * client must branch on them.</strong> Because withdrawal deletes, "already withdrawn"
     * is physically identical to "never existed", so it is a plain 404 — the same answer a
     * second DELETE of an already-removed member gets — and <strong>the client renders it as
     * success</strong>: the caller's goal ("this invitation must not be acceptable") is
     * already true, and the API states the truth about the resource while the client states
     * the truth about the intent. It must <em>not</em> extend that to the 409, which is why
     * that one carries an {@code errorType} and a detail naming the member and the People
     * screen.
     *
     * <p><strong>Withdrawing frees nothing measured over time.</strong> No
     * {@code mail_send_events} row is touched, so the per-(sender, inbox) invite cooldown and
     * the per-inbox daily cap survive a withdrawal intact and {@code invite → revoke →
     * invite} is refused exactly as {@code invite → invite} would be (HD-190, ADR-0015).
     * What it does free is stock — HD-133's uniqueness slot — and that needs no code: the row is
     * gone, so the partial unique index has nothing left to collide with. Since an unaccepted
     * invitation blocks a fresh one to the same address even after it expires, this DELETE is the
     * only way to re-offer access at a different role or with a new link, and the duplicate 409
     * sends its reader here by name.
     */
    @DeleteMapping("/{id}/invites/{inviteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeInvite(@AuthenticationPrincipal User user,
                             @PathVariable UUID id,
                             @PathVariable UUID inviteId) {
        workspaceService.revokeInvite(user, id, inviteId);
    }

    // The FOURTH token door, bounded for the reason the other three were and for no reason of its
    // own (HD-171 §4.4): same TokenUtils.generateRawToken, same 43 Base64url characters, and A RULE
    // ON ONE OF TWO DOORS IS NOT A RULE. What it is exposed to is genuinely small — the value is
    // SHA-256'd and looked up by hash, the caller is authenticated, and nothing builds a header out
    // of it, so this is the category and not an incident. 64 is the generator's width with room to
    // spare.
    //
    // It fires because this class carries NO @Validated: Spring MVC's built-in method validation
    // raises HandlerMethodValidationException, which Boot renders as a 400. @Validated on the class
    // would make HandlerMethod.shouldValidateArguments() return false and defer to the AOP proxy,
    // whose jakarta.validation.ConstraintViolationException nothing here handles — trading this
    // 400 for a 500. Do not add it.
    @PostMapping("/accept-invite")
    public WorkspaceResponse acceptInvite(@AuthenticationPrincipal User user,
                                          @RequestParam @Size(max = 64) String token) {
        return workspaceService.acceptInvite(user, token);
    }
}
