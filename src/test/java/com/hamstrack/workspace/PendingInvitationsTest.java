package com.hamstrack.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import com.hamstrack.common.security.JwtService;
import com.hamstrack.common.security.RoleScope;
import com.hamstrack.common.util.TokenUtils;
import com.hamstrack.workspace.entity.BuiltInRoles;
import com.hamstrack.workspace.entity.Workspace;
import com.hamstrack.workspace.entity.WorkspaceInvite;
import com.hamstrack.workspace.entity.WorkspaceMember;
import com.hamstrack.workspace.repository.WorkspaceInviteRepository;
import com.hamstrack.workspace.repository.WorkspaceMemberRepository;
import com.hamstrack.workspace.repository.WorkspaceRepository;
import com.hamstrack.workspace.service.RoleCatalog;
import com.hamstrack.workspace.service.WorkspaceService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>A workspace can finally see, and withdraw, the access it has offered</strong> (HD-158,
 * {@code docs/design/pending-invitations-proposal.md}).
 *
 * <p>Two endpoints, and the second one is the first write to {@code workspace_invites} performed by
 * somebody other than the invitee. That makes it this ticket's whole risk surface: a brand-new
 * <strong>id-taking</strong> endpoint on a table whose rows belong to one tenant. So this file
 * leads with tenancy — an invite id from workspace B, presented under workspace A by a member of A,
 * is a 404 <em>and the row in B is still there afterwards</em>. The status alone would pass an
 * implementation that deleted the row and then answered 404, which is why both halves are asserted,
 * and a third asserts that the workspace was part of the question rather than of a follow-up
 * {@code if}.
 *
 * <p>The two properties this file deliberately does not hold: the accept/withdraw race is
 * {@link InviteWithdrawRaceTest}, and "a withdrawal refunds no mail ceiling" is
 * {@link InviteThrottleBehaviourTest}, beside the decline that raises the identical question —
 * plus {@code MailSendEventRepositorySealTest}, which holds it structurally.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class PendingInvitationsTest {

    /** No SMTP in CI, and the one invitation sent through the real endpoint would attempt one. */
    @MockitoBean JavaMailSender mailSender;

    @Autowired MockMvc mockMvc;
    @Autowired JwtService jwtService;
    @Autowired RoleCatalog roleCatalog;
    @Autowired UserRepository userRepository;
    @Autowired WorkspaceRepository workspaceRepository;
    @Autowired WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired WorkspaceInviteRepository inviteRepository;
    @Autowired TransactionTemplate transactions;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired EntityManager em;

    private final ObjectMapper json = new ObjectMapper();

    // ================================================================ the list

    /**
     * <strong>Every unaccepted row, newest first, with expiry reported as a field</strong>
     * (AC 1 and AC 2).
     *
     * <p>The expired row is the one that matters. Nothing in this product sweeps one, a member
     * removal deletes one, and HD-133's uniqueness will refuse a re-invite over one — so a list
     * narrower than the table is a list that cannot explain the next refusal. It is asserted as a
     * <em>label beside a live row</em>, because "expired invitations are hidden" and "expired
     * invitations are labelled EXPIRED" are indistinguishable on a fixture holding one row.
     */
    @Test
    void theListIsEveryUnacceptedRowNewestFirstAndExpiryIsALabelNotAFilter() throws Exception {
        var owner = user("Olga Owner");
        var ws = workspaceOwnedBy(owner);

        var expired = plantInvite(ws, owner, address("lapsed"), "MEMBER",
                Instant.now().minusSeconds(60), null);
        var accepted = plantInvite(ws, owner, address("joined"), "MEMBER",
                Instant.now().plusSeconds(3600), Instant.now());
        // Through the real endpoint, so the newest row is one the product itself wrote.
        var live = address("waiting");
        invite(owner, ws, live).andExpect(status().isCreated());

        var rows = json.readTree(listInvites(owner, ws.getId()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(ids(rows))
                .as("an accepted invitation is history, not a standing grant: the membership row "
                    + "is the live fact, and a withdraw control beside something withdrawal cannot "
                    + "affect is a lie the screen tells for ever. An EXPIRED one is neither — "
                    + "nothing removes it, HD-133 will refuse a re-invite over it, and a row "
                    + "hidden here is a row no admin can clear and no refusal can name")
                .containsExactly(idOf(rows, live), expired)
                .doesNotContain(accepted);

        var first = rows.get(0);
        assertThat(first.get("email").asText()).isEqualTo(live);
        assertThat(first.get("role").asText()).isEqualTo("MEMBER");
        assertThat(UUID.fromString(first.get("roleId").asText()))
                .isEqualTo(BuiltInRoles.WORKSPACE_MEMBER);
        assertThat(UUID.fromString(first.get("invitedById").asText())).isEqualTo(owner.getId());
        assertThat(first.get("invitedByName").asText()).isEqualTo("Olga Owner");
        assertThat(first.get("status").asText()).isEqualTo("PENDING");
        assertThat(first.get("createdAt").isNull()).isFalse();
        assertThat(first.get("expiresAt").isNull()).isFalse();

        assertThat(rows.get(1).get("status").asText())
                .as("status is computed on the SERVER clock: a browser whose clock is skewed must "
                    + "not disagree with the endpoint that will accept or refuse the acceptance")
                .isEqualTo("EXPIRED");
    }

    /**
     * <strong>A corrupt role degrades the row, not the screen</strong> (AC 3) — and it withholds
     * the id together with the key.
     *
     * <p>The plant is a PROJECT-scoped role in a WORKSPACE invitation's {@code role_id}: no door in
     * the application will write one, since every write resolves ids through
     * {@code findAssignable} — which is exactly why the read path asserts rather than trusts.
     * Emitting the id beside the withheld key would hand the name back by proxy, the client looking
     * it up in the catalog and printing it.
     */
    @Test
    void anInvitationWithAWrongScopeRoleShowsNeitherAKeyNorAnIdAndTheRestOfTheListSurvives()
            throws Exception {
        var owner = user("Olga Owner");
        var ws = workspaceOwnedBy(owner);
        var healthy = plantInvite(ws, owner, address("healthy"), "MEMBER",
                Instant.now().plusSeconds(3600), null);
        var corrupt = plantInvite(ws, owner, address("corrupt"), "MEMBER",
                Instant.now().plusSeconds(1800), null);
        plantRoleId(corrupt, BuiltInRoles.PROJECT_MANAGER);

        var rows = json.readTree(listInvites(owner, ws.getId()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        var degraded = row(rows, corrupt);
        assertThat(degraded.get("role").isNull()).isTrue();
        assertThat(degraded.get("roleId").isNull())
                .as("the id of a role whose key was just refused must not be emitted — the client "
                    + "would resolve it against the catalog and render the name anyway")
                .isTrue();
        assertThat(rows.toString()).doesNotContain(BuiltInRoles.PROJECT_MANAGER.toString());
        assertThat(row(rows, healthy).get("role").asText())
                .as("one corrupt row must not empty an admin screen")
                .isEqualTo("MEMBER");
    }

    /**
     * <strong>The list costs the same whether it returns one row or ten</strong> (AC 4) — and it
     * fetches both associations rather than leaning on a global setting to hide that it does not.
     *
     * <p>Two assertions, because the first one cannot see what the second one is for.
     * <strong>The repository javadoc's claim that dropping either {@code JOIN FETCH} "makes this
     * N+1" is not true in this build</strong>, and the count proves it in the wrong direction:
     * with both fetch joins deleted, ten rows cost 5 statements and one row cost 5 too. The reason
     * is {@code spring.jpa.properties.hibernate.default_batch_fetch_size=100}, set instance-wide,
     * which turns "one select per lazy proxy" into one batched select per association — so the
     * would-be N+1 is already collapsed by something this endpoint does not own, does not
     * reference, and would lose silently if it were ever tuned down. (It is also only bounded up
     * to the batch size: a workspace with a long invitation history pays one select per hundred
     * rows.) The second assertion therefore asks the executed statement itself.
     *
     * <p>The fixture uses <strong>ten distinct inviters</strong>, not one repeated: ten rows sent
     * by one person cost the same fetched or lazy, because the persistence context answers the
     * second row from the first row's load. An N+1 is per distinct row, and a fixture with one
     * distinct value has no N to multiply.
     *
     * <p>Warmed first, because the role cache and the permission resolution are per-node and would
     * otherwise be charged to whichever request happened to run first rather than to the fetch plan.
     */
    @Test
    void theListRunsTheSameNumberOfStatementsForOneRowAsForTen() throws Exception {
        var lonely = user("Olga Owner");
        var small = workspaceOwnedBy(lonely);
        plantInvite(small, lonely, address("only"), "MEMBER", Instant.now().plusSeconds(3600), null);

        var busy = user("Owen Owner");
        var large = workspaceOwnedBy(busy);
        // TEN DISTINCT INVITERS, not one repeated. Ten rows sent by one person cost the same
        // whether the association is fetched or lazy — the persistence context resolves the second
        // row's inviter from the first row's load — so the homogeneous fixture this test was first
        // written with stayed green with the JOIN FETCH deleted. An N+1 is per distinct row, and a
        // fixture that has one is a fixture with no N to multiply.
        for (int i = 0; i < 10; i++) {
            var colleague = user("Colleague " + i);
            member(large, colleague, "ADMIN");
            plantInvite(large, colleague, address("colleague-" + i), "MEMBER",
                    Instant.now().plusSeconds(3600), null);
        }

        listInvites(lonely, small.getId()).andExpect(status().isOk());
        listInvites(busy, large.getId()).andExpect(status().isOk());

        var one = statementsDuring(() -> listInvites(lonely, small.getId()));
        var ten = statementsDuring(() -> listInvites(busy, large.getId()));

        assertThat(ten)
                .as("%d statements for ten invitations against %d for one — the list runs a "
                    + "bounded number of statements regardless of how many rows it returns "
                    + "(AC 4). A number that grows with the rows is a per-row read somebody "
                    + "added to the mapping loop", ten, one)
                .isEqualTo(one);

        var listQuery = queriesDuring(() -> listInvites(busy, large.getId())).stream()
                .filter(q -> q.contains("WorkspaceInvite"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the list ran no query over WorkspaceInvite"));
        assertThat(listQuery.replaceAll("[^A-Za-z0-9]+", " ").toLowerCase(Locale.ROOT))
                .as("the count above cannot see this, and that is measured rather than assumed: "
                    + "with BOTH fetch joins deleted the ten-row list cost 5 statements and the "
                    + "one-row list cost 5 too, so the equality stayed green. What collapses the "
                    + "would-be N+1 is the instance-wide "
                    + "spring.jpa.properties.hibernate.default_batch_fetch_size=100, which turns "
                    + "one select per row into one batched select per association — a setting this "
                    + "endpoint does not own and must not depend on, and one that degrades to a "
                    + "select per hundred rows on a workspace with a long invitation history. Both "
                    + "associations are LAZY and both are rendered on every row, so the query "
                    + "fetches both")
                .contains("join fetch i role")
                .contains("join fetch i invitedby");
    }

    // ================================================================ tenancy

    /**
     * <strong>An invite id from another workspace is a 404, and its row survives</strong> (AC 9) —
     * the property this ticket is likeliest to lose, on the only id-taking endpoint it adds.
     *
     * <p>Three assertions failing at three depths. The <em>status</em> says the caller learned
     * nothing: a member of A asking about B's id gets the answer an id nobody ever minted gets.
     * The <em>surviving row</em> says the refusal happened before the delete rather than after it —
     * a status-only test passes an implementation that deletes and then throws. And the
     * <em>statement</em> says the workspace was part of the question: the finder puts both keys in
     * one {@code WHERE}, so "simplify it to findById and compare the workspace in Java" fails here
     * even though it behaves identically today. That simplification is the shape this project's
     * top bug class arrives in — the comparison survives one refactor and not two.
     */
    @Test
    void anInviteIdFromAnotherWorkspaceIs404AndItsRowSurvives() throws Exception {
        var theirOwner = user("Bea Owner");
        var theirs = workspaceOwnedBy(theirOwner);
        var theirInvite = plantInvite(theirs, theirOwner, address("theirs"), "MEMBER",
                Instant.now().plusSeconds(3600), null);

        var myOwner = user("Olga Owner");
        var mine = workspaceOwnedBy(myOwner);

        var statements = queriesDuring(
                () -> revoke(myOwner, mine.getId(), theirInvite).andExpect(status().isNotFound()));

        assertThat(inviteRepository.findById(theirInvite))
                .as("the other tenant's invitation must still be there. A 404 returned AFTER the "
                    + "delete is the same status and a completely different product")
                .isPresent();

        assertThat(statements)
                .as("no statement in this request asked for an invitation by id AND by workspace "
                    + "together. The finder must be findByIdAndWorkspaceIdForUpdate(id, "
                    + "workspaceId): a bare findById followed by an if in Java behaves identically "
                    + "today and is one refactor away from not comparing at all, which is how one "
                    + "tenant comes to read another tenant's row. Executed: %s", statements)
                .anyMatch(PendingInvitationsTest::keyedOnBothIdAndWorkspace);
    }

    /**
     * <strong>Non-membership and non-existence are one answer, on both verbs</strong> (AC 9).
     * A stranger must not be able to tell a workspace that exists from one that does not — and the
     * id-taking verb must not become the oracle its sibling refuses to be.
     */
    @Test
    void bothVerbsAnswer404ToANonMemberAndToAnUnknownWorkspace() throws Exception {
        var owner = user("Olga Owner");
        var ws = workspaceOwnedBy(owner);
        var live = plantInvite(ws, owner, address("live"), "MEMBER",
                Instant.now().plusSeconds(3600), null);
        var stranger = user("Sam Stranger");

        listInvites(stranger, ws.getId()).andExpect(status().isNotFound());
        revoke(stranger, ws.getId(), live).andExpect(status().isNotFound());
        listInvites(stranger, UUID.randomUUID()).andExpect(status().isNotFound());
        revoke(stranger, UUID.randomUUID(), live).andExpect(status().isNotFound());

        assertThat(inviteRepository.findById(live))
                .as("a refused withdrawal is not a withdrawal")
                .isPresent();
    }

    /**
     * <strong>403 for a proven member without the permission, on both verbs</strong> (AC 8), with
     * the permission named — and the roster still readable, so the refusal is about this list and
     * not about the screen.
     *
     * <p>Not 404: tenancy is a different question and it was answered before this fired. Not an
     * empty array either — that would be a statement we know to be false, rendered as "no
     * invitations are waiting for a reply" on a workspace that has one.
     */
    @Test
    void aMemberWithoutMemberManageIsRefusedOnBothVerbsAndStillReadsTheRoster() throws Exception {
        var owner = user("Olga Owner");
        var ws = workspaceOwnedBy(owner);
        var live = plantInvite(ws, owner, address("live"), "MEMBER",
                Instant.now().plusSeconds(3600), null);
        var member = user("Mia Member");
        member(ws, member, "MEMBER");

        listInvites(member, ws.getId())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.containsString("workspace.member.manage")));
        revoke(member, ws.getId(), live)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.containsString("workspace.member.manage")));

        mockMvc.perform(get("/api/workspaces/" + ws.getId() + "/members")
                        .header("Authorization", bearer(member)))
                .andExpect(status().isOk());
        assertThat(inviteRepository.findById(live)).isPresent();
    }

    // ================================================================ the withdrawal

    /**
     * <strong>204, and the invitation stops being acceptable through every door it had</strong>
     * (AC 5 and AC 6).
     *
     * <p>The emailed token link and the by-id accept both resolve through finders that
     * {@code orElseThrow} a 404, so the delete is sufficient with no extra code — a claim about two
     * call sites, therefore asserted at both, plus at the invitee's own list. The second DELETE is
     * a plain 404: because withdrawal deletes, "already withdrawn" is physically identical to
     * "never existed", and the client is the layer that renders it as success.
     */
    @Test
    void withdrawalIs204AndClosesEveryDoorTheInvitationHadAndASecondOneIs404() throws Exception {
        var owner = user("Olga Owner");
        var ws = workspaceOwnedBy(owner);
        var inviteeAddress = address("invitee");
        var invitee = user("Ivy Invitee", inviteeAddress);
        var rawToken = TokenUtils.generateRawToken();
        var inviteId = plantInvite(ws, owner, inviteeAddress, "MEMBER",
                Instant.now().plusSeconds(3600), null, TokenUtils.sha256(rawToken));

        // The premise: while the row is live the invitee really can see it. Without this, every
        // assertion below could be passing because the fixture never worked.
        assertThat(pendingInviteIds(invitee)).contains(inviteId);

        revoke(owner, ws.getId(), inviteId).andExpect(status().isNoContent());

        assertThat(inviteRepository.findById(inviteId)).isEmpty();
        assertThat(pendingInviteIds(invitee)).doesNotContain(inviteId);
        mockMvc.perform(post("/api/workspaces/accept-invite")
                        .param("token", rawToken)
                        .header("Authorization", bearer(invitee)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/invites/" + inviteId + "/accept")
                        .header("Authorization", bearer(invitee)))
                .andExpect(status().isNotFound());
        revoke(owner, ws.getId(), inviteId).andExpect(status().isNotFound());
    }

    /**
     * <strong>An expired invitation is withdrawable</strong> (AC 5, §4.4). The list offers the
     * control on every row it shows, so it has to work on every row it shows — and since nothing
     * else in this product ever removes an expired row, refusing here would make the one class of
     * row that accumulates for ever the one class nobody can clear.
     */
    @Test
    void anExpiredInvitationIsWithdrawnWithA204() throws Exception {
        var owner = user("Olga Owner");
        var ws = workspaceOwnedBy(owner);
        var lapsed = plantInvite(ws, owner, address("lapsed"), "MEMBER",
                Instant.now().minusSeconds(600), null);

        revoke(owner, ws.getId(), lapsed).andExpect(status().isNoContent());
        assertThat(inviteRepository.findById(lapsed)).isEmpty();
    }

    /**
     * <strong>An accepted invitation is a 409 that names the member and the remedy</strong>
     * (AC 7), carrying {@code errorType} because the client must branch on it: it renders this
     * endpoint's 404 as success and must not render this one that way.
     *
     * <p>It is the one refusal here whose reader can act — "this person now has access" is exactly
     * the problem withdrawal was aimed at, and removing a member takes the permission they just
     * proved.
     */
    @Test
    void withdrawingAnAcceptedInvitationIs409NamingTheMemberAndTheScreen() throws Exception {
        var owner = user("Olga Owner");
        var ws = workspaceOwnedBy(owner);
        var joinedAddress = address("joined");
        var joined = user("Jo Joined", joinedAddress);
        var accepted = plantInvite(ws, owner, joinedAddress, "MEMBER",
                Instant.now().plusSeconds(3600), Instant.now());

        var response = revoke(owner, ws.getId(), accepted)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorType").value("INVITE_ALREADY_ACCEPTED"))
                .andReturn().getResponse().getContentAsString();

        assertThat(detail(response))
                .as("the reader can act on this one, so it says who and where: a 404 here would "
                    + "turn a real, actionable state into a phantom AND be rendered by the client "
                    + "as a withdrawal that never happened")
                .contains(joined.getDisplayName())
                .contains("People");
        assertThat(inviteRepository.findById(accepted))
                .as("a refused withdrawal deletes nothing")
                .isPresent();
    }

    // ================================================================ what it leaves behind

    /**
     * <strong>The revocation's audit line carries ids, the role and the recipient DOMAIN — never
     * the local part.</strong>
     *
     * <p>It is the only attribution a withdrawal leaves: after the 204 there is no row to join the
     * ids back to and the counter beside it is tagless, so without this line "who withdrew the
     * invitation to the new CFO, and when" is unanswerable anywhere in the system. Which is also
     * why it must not carry the address — the line ships to Loki, where an address would outlive
     * the {@code workspace_invites} row that legitimately held it.
     *
     * <p>The fixture address carries a {@code +tag} and mixed case on purpose: a redaction written
     * as "everything after the first @" or compared case-sensitively is one a plain address would
     * never catch.
     */
    @Test
    void theRevokedAuditLineNamesTheDomainAndNeverTheLocalPart() throws Exception {
        var owner = user("Olga Owner");
        var ws = workspaceOwnedBy(owner);
        var localPart = "Bob.Sender+HD158";
        var recipient = localPart + "-" + UUID.randomUUID().toString().substring(0, 8)
                        + "@Company.Example";
        var inviteId = plantInvite(ws, owner, recipient, "ADMIN",
                Instant.now().plusSeconds(3600), null);

        var lines = infoLinesDuring(
                () -> revoke(owner, ws.getId(), inviteId).andExpect(status().isNoContent()));

        var revoked = lines.stream().filter(l -> l.startsWith("workspace.invite.revoked")).toList();
        assertThat(revoked)
                .as("a withdrawal is a security-relevant act whose two neighbours "
                    + "(workspace.member.role_changed, workspace.member.removed) both log one, and "
                    + "it is the only trace left once the row is gone. Lines seen: %s", lines)
                .hasSize(1);

        var line = revoked.get(0);
        assertThat(line)
                .as("the ids an operator already has, plus the role that was being handed out")
                .contains(ws.getId().toString())
                .contains(owner.getId().toString())
                .contains(inviteId.toString())
                .contains("ADMIN")
                .contains("recipientDomain=company.example");
        assertThat(line)
                .as("the local part is what makes an address personal data, so it never appears — "
                    + "and neither does the address it belongs to, in any casing. Line: %s", line)
                .doesNotContain("@")
                .doesNotContain(localPart)
                .doesNotContain(localPart.toLowerCase(Locale.ROOT));
    }

    // ================================================================ fixture

    private ResultActions listInvites(User actor, UUID workspaceId) throws Exception {
        return mockMvc.perform(get("/api/workspaces/" + workspaceId + "/invites")
                .header("Authorization", bearer(actor)));
    }

    private ResultActions revoke(User actor, UUID workspaceId, UUID inviteId) throws Exception {
        return mockMvc.perform(delete("/api/workspaces/" + workspaceId + "/invites/" + inviteId)
                .header("Authorization", bearer(actor)));
    }

    private ResultActions invite(User actor, Workspace ws, String email) throws Exception {
        return mockMvc.perform(post("/api/workspaces/" + ws.getId() + "/invites")
                .header("Authorization", bearer(actor))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "role": "MEMBER"}
                        """.formatted(email)));
    }

    private List<UUID> pendingInviteIds(User invitee) throws Exception {
        var body = mockMvc.perform(get("/api/invites").header("Authorization", bearer(invitee)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return ids(json.readTree(body));
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.generateAccessToken(user);
    }

    private String detail(String problemJson) throws Exception {
        var node = json.readTree(problemJson).get("detail");
        return node == null ? "" : node.asText();
    }

    private static List<UUID> ids(JsonNode rows) {
        var out = new ArrayList<UUID>();
        rows.forEach(row -> out.add(UUID.fromString(row.get("id").asText())));
        return out;
    }

    private static UUID idOf(JsonNode rows, String email) {
        for (var row : rows) {
            if (row.get("email").asText().equals(email)) {
                return UUID.fromString(row.get("id").asText());
            }
        }
        throw new AssertionError("no row for " + email + " in " + rows);
    }

    private static JsonNode row(JsonNode rows, UUID id) {
        for (var row : rows) {
            if (row.get("id").asText().equals(id.toString())) {
                return row;
            }
        }
        throw new AssertionError("no row for " + id + " in " + rows);
    }

    /**
     * Whether one executed statement asked for an invitation by id <em>and</em> by workspace at
     * once. Read off Hibernate's own query statistics rather than off the source, so the answer
     * comes from what the database was asked rather than from a grep.
     */
    private static boolean keyedOnBothIdAndWorkspace(String query) {
        var normalised = query.replaceAll("[^A-Za-z0-9]+", " ").toLowerCase(Locale.ROOT);
        return normalised.contains("workspaceinvite")
               && normalised.contains("workspace id")
               && normalised.contains("id id");
    }

    /** The queries Hibernate recorded while {@code body} ran. */
    private List<String> queriesDuring(ThrowingRunnable body) throws Exception {
        var stats = statistics();
        stats.clear();
        body.run();
        return List.of(stats.getQueries());
    }

    private long statementsDuring(ThrowingRunnable body) throws Exception {
        var stats = statistics();
        stats.clear();
        body.run();
        return stats.getPrepareStatementCount();
    }

    private Statistics statistics() {
        var stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        return stats;
    }

    /** The INFO lines {@code WorkspaceService} emitted while {@code body} ran. */
    private List<String> infoLinesDuring(ThrowingRunnable body) throws Exception {
        var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(WorkspaceService.class);
        var appender = new ch.qos.logback.core.read.ListAppender<
                ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            body.run();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        return appender.list.stream()
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .toList();
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** Unique per run, so no two tests ever share a users row or a recipient key. */
    private static String address(String label) {
        return label + "-" + UUID.randomUUID().toString().substring(0, 12) + "@example.test";
    }

    private User user(String displayName) {
        return user(displayName, address("user"));
    }

    private User user(String displayName, String email) {
        var u = new User();
        u.setEmail(email.toLowerCase(Locale.ROOT));
        u.setDisplayName(displayName);
        u.setStatus(UserStatus.ACTIVE);
        u.setSystemRole(SystemRole.USER);
        return userRepository.save(u);
    }

    private Workspace workspaceOwnedBy(User owner) {
        var w = new Workspace();
        w.setName("Pending invitations " + UUID.randomUUID().toString().substring(0, 8));
        w.setSlug("pi-" + UUID.randomUUID().toString().substring(0, 12));
        w.setCreatedBy(owner);
        var saved = workspaceRepository.save(w);
        member(saved, owner, "OWNER");
        return saved;
    }

    private void member(Workspace ws, User user, String role) {
        var m = new WorkspaceMember();
        m.setWorkspace(ws);
        m.setUser(user);
        m.setRole(roleCatalog.reference(RoleScope.WORKSPACE, role));
        workspaceMemberRepository.save(m);
    }

    /**
     * A {@code workspace_invites} row written straight through the repository. The endpoint under
     * test is the <em>administrator's</em> view of this table, and three of the states it has to
     * render — expired, accepted, and a role the API refuses to write — are unreachable through
     * {@code POST /invites} by construction.
     */
    private UUID plantInvite(Workspace ws, User inviter, String email, String role,
                             Instant expiresAt, Instant acceptedAt) {
        return plantInvite(ws, inviter, email, role, expiresAt, acceptedAt,
                TokenUtils.sha256(TokenUtils.generateRawToken()));
    }

    private UUID plantInvite(Workspace ws, User inviter, String email, String role,
                             Instant expiresAt, Instant acceptedAt, String tokenHash) {
        var i = new WorkspaceInvite();
        i.setWorkspace(ws);
        i.setEmail(email.toLowerCase(Locale.ROOT));
        i.setRole(roleCatalog.reference(RoleScope.WORKSPACE, role));
        i.setTokenHash(tokenHash);
        i.setInvitedBy(inviter);
        i.setExpiresAt(expiresAt);
        i.setAcceptedAt(acceptedAt);
        return inviteRepository.save(i).getId();
    }

    /** Native, because no door in the application will write a wrong-scope role id. */
    private void plantRoleId(UUID inviteId, UUID roleId) {
        transactions.executeWithoutResult(status -> em.createNativeQuery(
                        "UPDATE workspace_invites SET role_id = :role WHERE id = :id")
                .setParameter("role", roleId)
                .setParameter("id", inviteId)
                .executeUpdate());
    }
}
