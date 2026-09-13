package com.hamstrack.common.ratelimit;

import com.hamstrack.common.testsupport.Doors;
import com.hamstrack.common.testsupport.Population;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>Every mutating handler under {@code /api/workspaces/**} is either behind a
 * per-principal budget that applies to its HTTP METHOD, or named in {@link #EXEMPT} with a written
 * reason</strong> (HD-191 §4.3, AC-19).
 *
 * <h2>Why the path-shaped seal could not express this</h2>
 * {@code ThrottleCoverageTest} asks whether a handler has a {@link PrincipalThrottleInterceptor} in
 * the chain the real handler mapping builds for it. That question is complete for the reports and
 * search budgets, whose surfaces are expensive in every verb. It is <em>incomplete</em> for the
 * write budget, which is method-conditioned on purpose: the path it covers
 * ({@code /api/workspaces/*}{@code /projects/*}{@code /issues/**}) is full of ordinary reads that
 * must stay unbudgeted, and one of them — the planning read surface — is the subject of an open
 * ticket ({@code PlanningThrottleParityTest} defends that emptiness deliberately). A path-shaped
 * assertion would report an interceptor "in front of" a {@code GET} that it never spends anything
 * on, which is coverage that is not coverage.
 *
 * <p>So this file inverts the axis from PATH to METHOD: it takes every
 * {@code POST}/{@code PUT}/{@code PATCH}/{@code DELETE} handler in the workspace-scoped API from
 * {@code Doors.writeHandlers()} (held equal to the runtime's handler map by
 * {@code DoorsHandlerMappingParityTest}) and asks each one whether the throttle in front of it — if
 * any — would actually charge that verb. The interceptor chain is a runtime fact, so the class
 * keeps its Spring context for that half.
 *
 * <h2>The exemptions are by CATEGORY, and each carries its own sentence</h2>
 * A list of individual controllers would be a list somebody maintains by remembering it exists.
 * Each exemption below is a category with an argument that survives a new endpoint joining it — no
 * count of them is given here, because a count goes stale one entry before the list does, and this
 * one did. Two shapes are worth knowing about: {@link #WORKSPACE_CREATION} is an exemption with
 * <strong>no good reason</strong>, which is the finding this test's polarity forces into the open
 * rather than fixes; and {@link #THE_INVITE_DOOR} is a single endpoint given a sentence of its own
 * because the category's sentence was true of only part of what it does.
 *
 * <h2>What it does NOT assert</h2>
 * How big a budget is, and whether a mutation is expensive. One interceptor applying to the verb
 * is the property; {@code WriteThrottleTest} owns the behaviour.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class WriteThrottleCoverageTest {

    /** Everything under here is in scope — the tenant-scoped API, which is all of the product's own writes. */
    private static final String SCOPE = "/api/workspaces";

    /**
     * <strong>Delivery-object writes</strong> — sprints and versions.
     *
     * <p>Not taxonomy and not administration: these are real objects with real work behind them
     * (starting a sprint writes the scope ledger, completing one moves every unfinished issue).
     * They are exempt on frequency and on shape rather than on cost: a sprint is started once a
     * fortnight and a version released once a release, both are gated on
     * {@code sprint.manage} / {@code version.manage}, and the one bulk shape among them
     * ({@code addIssues}) is bounded by {@code app.agile.*} caps — a ceiling on how much can
     * exist, which a request rate is not.
     *
     * <p><strong>The weakest of the four, and it is written down rather than smoothed over.</strong>
     * If a client ever drives sprint scope from a drag-and-drop surface the way the board drives
     * issue rank, this reason stops being true and the pattern in {@code WriteRateLimitConfig}
     * should grow rather than this list.
     */
    private static final String DELIVERY_OBJECTS =
            "sprint / version write: permission-gated, once-a-fortnight frequency, and the one bulk "
            + "shape among them is bounded by the app.agile.* caps rather than by a request rate";

    /**
     * <strong>Administrative and taxonomy writes.</strong> Permission-gated to curators and
     * administrators, low frequency by the nature of the work (nobody edits a workflow in a loop
     * to get value out of it), and bounded by the catalog they edit — every one of the
     * classification and set caps ({@code app.classification.*}, the role limit) is a ceiling on
     * how much of this can exist at all, which a request budget is not. Charging them to the write
     * pot would also starve an administrator's legitimate bulk edit to protect the issue surface,
     * which is the wrong trade in the wrong direction.
     */
    private static final String ADMIN_AND_TAXONOMY =
            "administrative / taxonomy write: permission-gated, low frequency, and bounded by the "
            + "catalog caps rather than by a request rate";

    /**
     * <strong>Membership and invitation writes.</strong> Already bounded, on a different axis:
     * HD-190's ceilings are keyed on the RECIPIENT and are spent inside the service, precisely
     * because a recipient-keyed refusal spent in an interceptor would answer a cross-tenant
     * question to a non-member. Adding a per-principal request budget on top would not bound
     * anything those ceilings do not already bound, and would put a second refusal shape on an
     * endpoint that already has two 429s meaning different things.
     *
     * <p>The property asserted is that each of these endpoints' <em>expensive</em> work is bounded on
     * an axis of its own, never that every refusal any of them makes is — see {@link #THE_INVITE_DOOR},
     * which is the member where that distinction has content.
     */
    private static final String MEMBERSHIP_AND_INVITES =
            "membership / invitation write: bounded by the recipient-keyed mail ceilings (HD-190), "
            + "which are spent in the service for tenancy reasons and cannot be an interceptor";

    /**
     * <strong>{@code POST …/invites} — the one member of the set above with a budget of its own and a
     * FREE BAND above it</strong> (HD-306 fix loop; the sentence it replaced said only "bounded by the
     * recipient-keyed mail ceilings", which is true of this endpoint's expensive half and of nothing
     * else it does).
     *
     * <p>{@code WorkspaceService.inviteMember}'s first charge is
     * {@code inviteThrottle.requireSenderVolume}. Every refusal above that line — the unknown role
     * (422), both grant-ceiling refusals (403), the post-fold address bound (400, HD-306) and the
     * already-a-member probe (409) — costs the caller nothing on this endpoint: no mail ceiling, no
     * sender budget, and no {@code PrincipalThrottleInterceptor}, which does not cover this path. That
     * is deliberate and positional (the invariant is stated at that method), and it is why the band is
     * kept to checks that cost one comparison and no state. It is recorded here because a reader of the
     * exemption above would otherwise conclude that everything this door refuses is budgeted.
     */
    private static final String THE_INVITE_DOOR =
            "invitation write: its FIRST CHARGE is the per-sender volume budget and its expensive half "
            + "the recipient-keyed mail ceilings (HD-190), both spent in the service for tenancy "
            + "reasons and neither expressible in an interceptor. The band of refusals ABOVE the "
            + "sender half is bounded by nothing on this endpoint, by design — so it holds only "
            + "checks decided from the request in one comparison";

    /**
     * <strong>Saved-filter writes.</strong> Already on the SEARCH budget
     * ({@code SearchRateLimitConfig.FILTERS_PATH}) — a saved filter is a saved search, and creating
     * one validates its HQL through the same {@code ResolutionContext} build {@code /search/schema}
     * pays for. Being on one per-principal budget is the property; being on two would double-charge
     * one action.
     */
    private static final String ON_THE_SEARCH_BUDGET =
            "saved-filter write: already on the search budget, because validating a filter's HQL "
            + "is search-surface work wherever it is mounted";

    /**
     * <strong>{@code POST /api/workspaces} — workspace creation, and there is no good reason.</strong>
     *
     * <p>This test's polarity forces every mutating handler to be named, and this is the one that
     * cannot be defended. Workspace creation is unbudgeted, public signup is on in production, and
     * ADR-0015 already records that "creating workspaces is bounded by nothing" is half of the
     * invitation-abuse attack.
     *
     * <p>HD-191 does not fix it, deliberately: a workspace-creation budget is a different KEY
     * (creation is not under a workspace, so there is nothing tenant-scoped to bind to), a different
     * denomination, and a different set of legitimate-use questions — first-login onboarding creates
     * one per user, so the naive bound refuses the product's own happy path. What changes here is
     * that it stops being invisible. Filed as a follow-up.
     */
    private static final String WORKSPACE_CREATION =
            "workspace creation: UNBUDGETED, and this is a finding rather than a justification — "
            + "see the constant's javadoc and the follow-up ticket. Do not copy this reason";

    /**
     * What may be unthrottled, and why — keyed either by {@code ControllerSimpleName} (the whole
     * type) or by {@code ControllerSimpleName#methodName} (one endpoint — {@code Doors.Handler#id()},
     * the one spelling every category test keys on), with the method key winning.
     *
     * <p><strong>Two granularities, because the exemptions are genuinely of two kinds.</strong>
     * {@code AuthMailDoorsTest} seals to the method and argues that a file is not the unit at which
     * the decision is made — correct there, where three anonymous auth flows with three different
     * disclosure properties share one file. Here most of the decision really is a property of the
     * whole type: every handler on {@code ProjectAdminController} is a taxonomy write, and listing
     * forty-four of them would be a list maintained by whoever remembers it exists — the artefact
     * this file's polarity is meant to replace. So a type key says "this entire surface is
     * category X".
     *
     * <p>Where a controller mixes categories the key drops to the method, and that is not a
     * convenience: {@code WorkspaceController} carries workspace creation (exempt with <em>no good
     * reason</em>), workspace settings (bounded by the catalog they edit) and the invitation and
     * membership writes (bounded by the recipient-keyed mail ceilings). A type-level exemption
     * there would hand the "no good reason" one an argument written about the other two, which is
     * exactly how a reason outlives its subject.
     *
     * <p>The residual risk of a type key is stated rather than hidden: a NEW mutation added to an
     * already-exempt controller inherits its category silently. That is acceptable only while the
     * category is a property of the controller's whole purpose — so if you add an endpoint to one
     * of these that does not fit its sentence, split the entry into method keys in the same commit.
     */
    private static final java.util.Map<String, String> EXEMPT = java.util.Map.ofEntries(
            // --- mixed-category controllers: keyed per endpoint ---
            java.util.Map.entry("WorkspaceController#create", WORKSPACE_CREATION),
            java.util.Map.entry("WorkspaceController#update", ADMIN_AND_TAXONOMY),
            java.util.Map.entry("WorkspaceController#previewProjectAccess", ADMIN_AND_TAXONOMY),
            java.util.Map.entry("WorkspaceController#invite", THE_INVITE_DOOR),
            java.util.Map.entry("WorkspaceController#revokeInvite", MEMBERSHIP_AND_INVITES),
            java.util.Map.entry("WorkspaceController#acceptInvite", MEMBERSHIP_AND_INVITES),
            java.util.Map.entry("WorkspaceController#updateMember", MEMBERSHIP_AND_INVITES),
            java.util.Map.entry("WorkspaceController#removeMember", MEMBERSHIP_AND_INVITES),

            java.util.Map.entry("ProjectController#create", ADMIN_AND_TAXONOMY),
            java.util.Map.entry("ProjectController#update", ADMIN_AND_TAXONOMY),
            java.util.Map.entry("ProjectController#archive", ADMIN_AND_TAXONOMY),
            java.util.Map.entry("ProjectController#unarchive", ADMIN_AND_TAXONOMY),
            java.util.Map.entry("ProjectController#setDefaultRole", ADMIN_AND_TAXONOMY),
            java.util.Map.entry("ProjectController#addMember", MEMBERSHIP_AND_INVITES),
            java.util.Map.entry("ProjectController#updateMember", MEMBERSHIP_AND_INVITES),
            java.util.Map.entry("ProjectController#removeMember", MEMBERSHIP_AND_INVITES),

            // --- single-category controllers: keyed by type ---
            java.util.Map.entry("WorkspaceAdminController", ADMIN_AND_TAXONOMY),
            java.util.Map.entry("ProjectAdminController", ADMIN_AND_TAXONOMY),
            java.util.Map.entry("LabelController", ADMIN_AND_TAXONOMY),
            java.util.Map.entry("ComponentController", ADMIN_AND_TAXONOMY),
            java.util.Map.entry("RoleController", ADMIN_AND_TAXONOMY),
            java.util.Map.entry("SprintController", DELIVERY_OBJECTS),
            java.util.Map.entry("VersionController", DELIVERY_OBJECTS),
            java.util.Map.entry("SavedFilterController", ON_THE_SEARCH_BUDGET));

    /**
     * The failure message, which is the whole point of the file: a maintainer who lands here has
     * added a mutating endpoint and has exactly two correct moves.
     */
    private static final String WHAT_TO_DO = """

            A MUTATING HANDLER UNDER /api/workspaces/** WITH NO BUDGET THAT APPLIES TO ITS VERB.

            Two correct moves, and "leave it" is neither:

              1. PUT IT UNDER A BUDGET. If it hangs off an issue, it already is — \
            WriteRateLimitConfig.WRITE_PATH covers /api/workspaces/*/projects/*/issues/** for \
            POST/PUT/PATCH/DELETE, so check the pattern really matches your mapping rather than \
            assuming it does ("the pattern does not match the endpoint you thought" is a silent \
            failure: everything works, unthrottled). If it is somewhere else and does comparable \
            work, add the pattern there and edit ThrottleCoverageTest's seal in the SAME commit — \
            its failure message is the propagation checklist.

              2. EXEMPT IT WITH A REASON THAT SURVIVES THE QUESTION. Add the method to EXEMPT \
            above against one of the four category constants, or write a fifth. The question each \
            has to answer is: what is the most expensive thing one authenticated caller can make \
            this handler do in a loop, and what already bounds it? "It is administrative" is not \
            an answer on its own; "it is bounded by the catalog cap it edits" is.

            Note that EXEMPT is keyed on the METHOD, not the controller. That is deliberate: a \
            file-granular exemption would let a new mutation appear inside an already-exempt \
            controller with nothing failing.

            And note what a budget here is NOT. It bounds the RATE at which one principal may \
            write. It does not bound BYTES (that is app.write.upload-bytes-per-minute, spent at \
            the attachment door because its cost is the parsed part size) and it does not bound \
            a TENANT's cumulative storage (that is the workspace quota, which is in PostgreSQL \
            because a bound on a bill may not divide by replica count). If your endpoint hands \
            bytes to FileStorage, AttachmentDoorsTest is the seal you also have to satisfy.
            """;

    @Autowired
    RequestMappingHandlerMapping handlerMapping;

    /**
     * The other outcome, which is not "unbudgeted": {@code Doors} counts classes and this class
     * checks beans, so a handler on a {@code @Conditional}-meta class whose property this context
     * does not set has no chain at all. Reporting that as unbudgeted would hand the maintainer
     * {@link #WHAT_TO_DO}'s two moves, neither of which is the fix.
     */
    private static final String NOT_ROUTED = """

            A CONDITIONAL HANDLER THIS CONTEXT DOES NOT ROUTE, SO ITS BUDGET WAS NOT CHECKED.

            %s

            Doors counts classes, not beans: a handler on a @Conditional-meta class is a member \
            (marked [conditional]) whether or not the property that instantiates it is set, and \
            this test's Spring context never registered the handlers above. An absent chain is \
            not "unbudgeted" and it is not coverage either — it is a door this test did not look \
            at. Two correct moves: enable its property in this class's @SpringBootTest property \
            set (DoorsHandlerMappingParityTest carries the set with every condition on), or add \
            it to EXEMPT with a reason that survives the question in WHAT_TO_DO.
            """;

    /** What the runtime says about one (verb, URI): a budget charges it, a chain exists but nothing charges it, or nothing routes it. */
    private enum Budget { CHARGED, NOT_CHARGED, NOT_ROUTED }

    @Test
    void everyMutatingWorkspaceHandlerIsBudgetedOrExemptWithAReason() throws Exception {
        var probed = new ArrayList<String>();
        var unbudgeted = new LinkedHashSet<String>();
        var notRouted = new LinkedHashSet<String>();

        var tenantWrites = Doors.writeHandlers().floor(150)
                .filter("tenant API", h -> h.paths().stream().anyMatch(p -> p.startsWith(SCOPE)));
        for (var handler : tenantWrites) {
            var type = handler.bean().getSimpleName();
            var name = handler.id();
            for (var path : handler.paths()) {
                if (!path.startsWith(SCOPE)) {
                    continue;
                }
                // Handler.writeVerbs(): the mutating verbs, all four when the mapping has no
                // method condition — the rule lives on the population, not in this file.
                for (var verb : handler.writeVerbs()) {
                    var method = verb.name();
                    probed.add(method + " " + path);
                    // The method key wins, so a mixed-category controller can name one endpoint
                    // without the type key covering the rest.
                    if (EXEMPT.containsKey(name) || EXEMPT.containsKey(type)) {
                        continue;
                    }
                    Budget budget;
                    try {
                        budget = budget(method, concrete(path));
                    } catch (HttpRequestMethodNotSupportedException notThisVerb) {
                        // "Not routed" has a second runtime shape: SpaController's catch-all GET forward
                        // matches every dotless path, so a verb nobody registered answers 405 from
                        // getHandler rather than a null chain (measured with SCOPE widened to /api).
                        // For a non-conditional handler that is a Doors⇄runtime disagreement the
                        // parity test owns, and it propagates exactly as it always did.
                        if (!handler.conditional()) {
                            throw notThisVerb;
                        }
                        budget = Budget.NOT_ROUTED;
                    }
                    if (budget == Budget.NOT_ROUTED && handler.conditional()) {
                        notRouted.add(method + " " + path + "  — this context does not route `" + name
                                      + "` — Doors counts classes (`[conditional]`); enable its property in this "
                                      + "test's property set or exempt it with the reason");
                    } else if (budget != Budget.CHARGED) {
                        // A non-conditional handler nothing routes is a Doors⇄runtime disagreement the
                        // parity test owns; here it stays what it always was — a write with no budget.
                        unbudgeted.add(method + " " + path + "  (" + name + ")");
                    }
                }
            }
        }

        // Tripwire: a probe that matched nothing would pass the assertion below while guarding an
        // empty set — the failure this file exists to prevent, so it is checked rather than assumed.
        assertThat(probed)
                .as("the handler mapping produced almost no mutating workspace-scoped handlers, so "
                    + "this test is guarding an empty set — the API base path moved, or the "
                    + "controllers did")
                .hasSizeGreaterThan(30);

        assertThat(notRouted).as(NOT_ROUTED, String.join("\n", notRouted)).isEmpty();
        assertThat(unbudgeted).as(WHAT_TO_DO).isEmpty();
    }

    /**
     * <strong>Every exemption names a handler that still exists.</strong> The opposite failure to
     * the one above and just as quiet: a stale entry is an exemption sitting over nothing, and the
     * next handler that happens to take the same name inherits a reason written about something
     * else.
     */
    @Test
    void everyExemptionNamesALiveHandler() {
        var live = new LinkedHashSet<String>();
        for (var handler : Doors.handlers().floor(250)) {
            live.add(handler.bean().getSimpleName());
            live.add(handler.id());
        }

        // Population.excluding is the assertion: it refuses, naming the key and saying what to do
        // with it, any exemption whose subject is not a live controller type or handler id.
        var remaining = Population.of("controller types and handler ids (Doors.handlers())", List.copyOf(live))
                .excluding("EXEMPT in WriteThrottleCoverageTest", EXEMPT.keySet());
        assertThat(remaining.size()).isEqualTo(live.size() - EXEMPT.size());
    }

    /**
     * Whether the real handler chain for this URI carries a {@link PrincipalThrottleInterceptor}
     * that <strong>applies to this verb</strong> — or no chain at all, which the caller tells
     * apart from "a chain nothing charges" for a conditional handler.
     *
     * <p>The applies-to-verb half is the whole reason this file exists: a method-conditioned
     * interceptor is present in the chain of a {@code GET} it never charges, so {@code instanceof}
     * alone would report coverage that does not exist.
     */
    private Budget budget(String method, String uri) throws Exception {
        var request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        ServletRequestPathUtils.parseAndCache(request);
        var chain = handlerMapping.getHandler(request);
        if (chain == null) {
            return Budget.NOT_ROUTED;
        }
        var charged = chain.getInterceptorList().stream()
                .filter(PrincipalThrottleInterceptor.class::isInstance)
                .map(PrincipalThrottleInterceptor.class::cast)
                .anyMatch(i -> i.appliesTo(method));
        return charged ? Budget.CHARGED : Budget.NOT_CHARGED;
    }

    /** A concrete URI for a mapped pattern: every {@code {var}} becomes a UUID. */
    private static String concrete(String pattern) {
        return pattern.replaceAll("\\{[^/}]*}", UUID.randomUUID().toString());
    }
}
