package com.hamstrack.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * <strong>{@code CSP_REPORT_ONLY_ENABLED=false} removes the header and changes nothing else</strong>
 * (HD-264).
 *
 * <p>Worth its own context because "off" has a wrong implementation that looks right: emitting the
 * header with an empty value. A proxy, a scanner and a security questionnaire all still see a
 * {@code Content-Security-Policy-Report-Only} then, and "the operator turned it off" has to be
 * indistinguishable from "this release does not have it".
 *
 * <p>The rest of the security header block is asserted to survive, because the off switch is a
 * property of one header and not of {@code SecurityConfig}'s {@code .headers(…)} block: a
 * regression that returned early from the wrong place would take {@code nosniff} and the frame
 * refusal with it, and nothing else in the suite is looking at that.
 */
@SpringBootTest(properties = {
        "app.csp.report-only-enabled=false",
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class ContentSecurityPolicyDisabledTest {

    @Autowired MockMvc mockMvc;

    @Test
    void theHeaderIsAbsentEntirelyAndTheRestOfTheBlockIsIntact() throws Exception {
        var response = mockMvc.perform(get("/")).andReturn().getResponse();

        assertThat(response.getHeader("Content-Security-Policy-Report-Only"))
                .as("absent, not empty — an empty policy is still a header everything downstream "
                    + "can see, and it measures nothing")
                .isNull();
        assertThat(response.getHeader("Content-Security-Policy")).isNull();
        assertThat(response.getHeader("X-Content-Type-Options"))
                .as("the off switch belongs to ONE header; taking the block with it would be a "
                    + "silent downgrade of every response")
                .isEqualTo("nosniff");
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
    }
}
