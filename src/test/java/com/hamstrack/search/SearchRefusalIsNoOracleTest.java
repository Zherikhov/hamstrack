package com.hamstrack.search;

import com.hamstrack.common.dto.Paging;
import com.hamstrack.issue.LabelTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * <strong>HD-163/HD-214 AC-9 — a refusal at the edge must not become an existence oracle.</strong>
 *
 * <p>Every constraint added by this ticket fires during argument resolution, which is <em>before</em>
 * {@code WorkspaceAccessService.requireMember} runs. So a member, an authenticated non-member and
 * the sender of a workspace id that does not exist all receive the same 400. Against this project's
 * strongest reflex — "non-existence and non-membership are both 404" — that reads like a leak, and
 * it is the opposite of one.
 *
 * <p><strong>The invariant is that non-existence and non-membership are indistinguishable from ONE
 * ANOTHER.</strong> Here they are, because both are identical to what a member gets: the response
 * reveals only that the route exists, which the published OpenAPI document already states. The leak
 * would be the other outcome — a 400 for a member and a 404 for a stranger on the same malformed
 * request turns a validation error into a membership probe, and the 404 rule would be defeated
 * without a line of it being edited.
 *
 * <p><strong>What that rests on, stated as a prohibition rather than an observation</strong> (spec
 * §5.1, extending ADR-0017 from bodies to parameters): <em>every constraint on a workspace-scoped
 * request must be a pure function of the request.</em> {@code @Size} and {@code @Max} read only the
 * submitted value; they cannot consult the database, so their answer cannot differ between a member
 * and a stranger. The moment one asks the database anything — "is this a real project?", "is this
 * address already a member?" — the identical 400 silently becomes an oracle. If this file ever goes
 * red, the validator is what to fix, never this file.
 *
 * <p><strong>The complementary half is what stops the first half being trivially satisfiable.</strong>
 * "Everybody gets the same answer" is also true of an endpoint that answers 400 to everything, so
 * each probe is paired with its WELL-FORMED twin, which must still answer 404 for the non-member and
 * the unknown workspace. Together they say: the refusal is blind to who is asking, and the request
 * that gets past the refusal is not.
 *
 * <p>One member of the body is expected to differ for the third caller and is normalised away:
 * problem+json echoes the request URI back as {@code instance}, filled by the framework, and that
 * caller sends a different URI. It carries nothing the server knows. Member and non-member send
 * byte-identical requests and are compared with nothing normalised at all — handled exactly as
 * HD-171's {@code InviteRequestBoundTest} handled it.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class SearchRefusalIsNoOracleTest extends LabelTestBase {

    private static final String PAST_BOUND = "a".repeat(101);

    /**
     * A probe is a request shape parameterised by workspace id: the {@code refused} variant carries
     * the out-of-range value, the {@code wellFormed} twin is the same request with a value the
     * server accepts.
     */
    private record Probe(String id,
                         Function<UUID, MockHttpServletRequestBuilder> refused,
                         Function<UUID, MockHttpServletRequestBuilder> wellFormed) {}

    private static final List<Probe> PROBES = List.of(
            new Probe("over-long q on …/search/suggest",
                    ws -> suggestRequest(ws, "assignee", PAST_BOUND),
                    ws -> suggestRequest(ws, "assignee", "ab")),
            new Probe("over-long field on …/search/suggest",
                    ws -> suggestRequest(ws, PAST_BOUND, "ab"),
                    ws -> suggestRequest(ws, "assignee", "ab")),
            new Probe("out-of-range page on POST …/search",
                    ws -> searchRequest(ws, (long) Paging.MAX_PAGE + 1),
                    ws -> searchRequest(ws, 0)));

    private Ctx ctx;
    private String strangerToken;

    @BeforeEach
    void setUp() throws Exception {
        if (ctx == null) {
            ctx = newProject();
            strangerToken = login(user());
        }
    }

    /**
     * The three callers get the same status and the same body. Asserted per probe with the probe's
     * name in the message, because "one of the three refusals leaks" is the only useful thing a
     * failure here can say.
     */
    @Test
    void everyEdgeRefusalLooksTheSameToAMemberAStrangerAndAnUnknownWorkspace() throws Exception {
        var offenders = new ArrayList<String>();

        for (var probe : PROBES) {
            var asMember = answer(probe.refused().apply(ctx.wsId()), ctx.token());
            var asStranger = answer(probe.refused().apply(ctx.wsId()), strangerToken);
            var unknownWorkspace = answer(probe.refused().apply(UUID.randomUUID()), strangerToken);

            if (!asStranger.equals(asMember)) {
                offenders.add(probe.id() + " — a NON-MEMBER got a different answer than a member on "
                              + "a byte-identical request:\n  member:  " + asMember
                              + "\n  stranger: " + asStranger);
            }
            if (!withoutInstance(unknownWorkspace).equals(withoutInstance(asMember))) {
                offenders.add(probe.id() + " — an UNKNOWN workspace id got a different answer than a "
                              + "member:\n  member:  " + asMember
                              + "\n  unknown: " + unknownWorkspace);
            }
            assertThat(unknownWorkspace.body())
                    .as("%s — the only thing that may differ for the third caller is the URI it "
                        + "sent back to itself", probe.id())
                    .contains("\"instance\":\"/api/workspaces/");
        }

        assertThat(offenders)
                .as("""
                        A REFUSAL AT THE REQUEST EDGE HAS BECOME A MEMBERSHIP ORACLE.

                        These requests are identical on the wire, so a difference in the responses \
                        can only come from WHO SENT THEM — which is exactly the signal the "404 for \
                        both non-existence and non-membership" rule exists to withhold, arriving \
                        through a 400 instead.

                        The cause is almost never this test. It is a constraint on a \
                        workspace-scoped request that stopped being a pure function of the request: \
                        a ConstraintValidator that consults the database, or a bound moved out of \
                        the annotation and into a service that resolves membership first. Put the \
                        refusal back at the edge, or make the validator answer from the submitted \
                        value alone.""")
                .isEmpty();
    }

    /**
     * The complementary half. Remove the offending value and the same three callers separate
     * immediately: the member is served, and the other two get 404 — which is what proves the
     * identical refusal above is validation running early rather than tenancy having stopped
     * running at all.
     */
    @Test
    void aWellFormedRequestStillSeparatesAMemberFromEveryoneElse() throws Exception {
        var offenders = new ArrayList<String>();

        for (var probe : PROBES) {
            int member = answer(probe.wellFormed().apply(ctx.wsId()), ctx.token()).status();
            int stranger = answer(probe.wellFormed().apply(ctx.wsId()), strangerToken).status();
            int unknown = answer(probe.wellFormed().apply(UUID.randomUUID()), strangerToken).status();

            if (member != 200) {
                offenders.add(probe.id() + " — the well-formed twin must be ACCEPTED for a member, "
                              + "was " + member + "; otherwise this test compares two refusals and "
                              + "proves nothing about tenancy");
            }
            if (stranger != 404) {
                offenders.add(probe.id() + " — a non-member sending a well-formed request got "
                              + stranger + ", not 404");
            }
            if (unknown != 404) {
                offenders.add(probe.id() + " — an unknown workspace id got " + unknown + ", not 404");
            }
        }

        assertThat(offenders)
                .as("""
                        Tenancy must still hold for a request that gets past validation. A \
                        non-member and a non-existent workspace are both 404 — never 403, which \
                        would confirm the workspace exists, and never 200.""")
                .isEmpty();
    }

    // ------------------------------------------------------------------ plumbing

    private record Answer(int status, String body) {}

    private Answer answer(MockHttpServletRequestBuilder request, String token) throws Exception {
        var response = mockMvc.perform(request.header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
        return new Answer(response.getStatus(), response.getContentAsString());
    }

    /** Drops the {@code instance} member, which is the request URI echoed back verbatim. */
    private static String withoutInstance(Answer answer) {
        return answer.status() + " " + answer.body().replaceAll("\"instance\":\"[^\"]*\",?", "");
    }

    private static MockHttpServletRequestBuilder suggestRequest(UUID workspaceId, String field, String q) {
        return get("/api/workspaces/" + workspaceId + "/search/suggest")
                .param("field", field)
                .param("q", q);
    }

    private static MockHttpServletRequestBuilder searchRequest(UUID workspaceId, long page) {
        return post("/api/workspaces/" + workspaceId + "/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"\",\"page\":" + page + ",\"size\":100}");
    }
}
