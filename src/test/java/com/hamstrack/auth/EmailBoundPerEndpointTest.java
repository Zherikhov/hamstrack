package com.hamstrack.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <strong>HD-171 AC 1 and AC 12 — the two mail doors {@code EmailLengthBoundTest} does not drive,
 * and what must NOT happen behind them.</strong>
 *
 * <p>{@code EmailLengthBoundTest} holds the category claim (every {@code @Email} in production
 * source carries a {@code @Size(max = 255)}) and drives two endpoints end to end. AC 1 asks for the
 * assertion <em>per endpoint</em>, and these two had none:
 * {@code POST /api/auth/forgot-password} and {@code POST /api/auth/resend-verification}. The third,
 * {@code POST /api/workspaces/{ws}/invites}, needs a member context and lives in
 * {@code InviteRequestBoundTest}.
 *
 * <p><strong>The status is only half of AC 12.</strong> These two endpoints are deliberately
 * enumeration-safe — they answer the same thing whether or not an account exists — so a 400 is a
 * <em>visible</em> change to them and the invisible half is what this class exists for: the refusal
 * happens during argument resolution, before the handler body runs, so <strong>nothing downstream
 * of the controller may observe the request at all</strong>. No mail may be attempted, and no
 * {@code mail_send_events} row (the recipient-keyed ledger the abuse throttles read) may be
 * written. A refusal that spent a recipient's daily budget would let an unauthenticated caller
 * exhaust somebody else's allowance with bodies the server never accepted.
 *
 * <p><strong>Why a 400 does not weaken the enumeration guarantee</strong>, since a reviewer will
 * ask: the length of the submitted string is a property of the string, known to whoever typed it,
 * and the same 400 comes back for an address that exists, one that does not, and one that could
 * never exist. Nothing about the account database is disclosed.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "app.legal.terms-acceptance-required=false",
        "app.registration.public-signup-enabled=true",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class EmailBoundPerEndpointTest {

    @Autowired MockMvc mockMvc;
    @Autowired DataSource dataSource;

    @MockitoBean JavaMailSender mailSender;

    @Test
    void forgotPasswordRefusesAnOverlongAddressWithoutSendingOrRecordingAnything() throws Exception {
        assertRefusedAndSilent("/api/auth/forgot-password");
    }

    @Test
    void resendVerificationRefusesAnOverlongAddressWithoutSendingOrRecordingAnything() throws Exception {
        assertRefusedAndSilent("/api/auth/resend-verification");
    }

    /**
     * One shape, two doors — phrased once because the claim is about what an unauthenticated mail
     * door may do with a body it refuses, not about either endpoint's own business.
     */
    private void assertRefusedAndSilent(String path) throws Exception {
        long before = mailSendEvents();

        mockMvc.perform(post(path)
                        .contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + overlongAddress() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").exists());

        verifyNoInteractions(mailSender);
        assertThat(mailSendEvents())
                .as("""
                        %s wrote a mail_send_events row for a request it refused. The refusal \
                        happens during argument resolution, before the handler body runs, so \
                        nothing downstream of the controller may observe it — a ledger row here \
                        would let an unauthenticated caller spend a real recipient's daily budget \
                        with bodies the server never accepted.""", path)
                .isEqualTo(before);
    }

    private long mailSendEvents() throws SQLException {
        try (var conn = dataSource.getConnection();
             var st = conn.createStatement();
             var rs = st.executeQuery("SELECT count(*) FROM mail_send_events")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * 300 characters and <em>valid</em>: a 60-character local part (the limit is 64) and a
     * 239-character domain of labels no longer than 63. The same fixture
     * {@code EmailLengthBoundTest} proves is refused by the length bound rather than by
     * {@code @Email} — which is what makes the 400 here mean what it says.
     */
    private static String overlongAddress() {
        var label = "b".repeat(59);
        var address = "a".repeat(60) + "@" + label + "." + label + "." + label + "."
                      + "c".repeat(55) + ".com";
        assertThat(address).hasSize(300);
        return address;
    }
}
