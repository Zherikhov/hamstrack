package com.hamstrack.common.security;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * <strong>The budget is spent per LOG LINE, not per request</strong> (HD-264).
 *
 * <p>This is the arithmetic two separate seals each half-covered and neither multiplied: one test
 * asserts the per-field byte bounds of <em>one</em> report, another asserts that a request may
 * deliver <em>twenty</em>, and a third asserts a per-IP budget counted in <em>requests</em>. Every
 * volume figure the feature wrote — in this class's own javadoc, in {@code CspProperties}, in
 * {@code application.properties}, in {@code .env.prod.example} and in {@code docs/self-hosting.md}
 * — was the product of the first and the third while the second sat between them, so each was a
 * 20× understatement. Bounding a line's length and bounding a request's rate does not bound
 * volume; only counting the thing being produced does.
 *
 * <p>So the pot is charged where the cost is incurred. The guard filter spends one token to admit
 * the request — which pays for the first line — and every further line asks again. That also
 * survives the enforcement ticket without a second decision: {@code Reporting-Endpoints} batches
 * by design, and a per-request budget would have had to be re-derived the day it arrives.
 *
 * <p>Its own context because it needs a small budget, and a small budget starves the neighbours
 * that assert about lines rather than about the pot — the same reason
 * {@code CspReportInstanceBudgetTest} has one.
 */
@SpringBootTest(properties = {
        "app.csp.sink-enabled=true",
        "app.csp.reports-per-minute-per-ip=5",
        "app.csp.reports-per-minute=10000",
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email="
})
@AutoConfigureMockMvc
class CspReportBatchBudgetTest {

    private static final String REPORT =
            "{\"csp-report\":{\"document-uri\":\"http://localhost:8080/\","
            + "\"effective-directive\":\"img-src\"}}";

    @Autowired MockMvc mockMvc;
    @Autowired MeterRegistry meterRegistry;

    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

    @BeforeEach
    void attachAppender() {
        logged.start();
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(CspReportSink.class))
                .addAppender(logged);
    }

    @AfterEach
    void detachAppender() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(CspReportSink.class))
                .detachAppender(logged);
        logged.stop();
    }

    @Test
    void oneRequestCarryingTenReportsWritesOnlyAsManyLinesAsTheBudgetPaysFor() throws Exception {
        double dropsBefore = dropped("budget");
        var batch = new StringBuilder("[").append(REPORT);
        for (int i = 1; i < 10; i++) {
            batch.append(",").append(REPORT);
        }

        assertThat(status(batch.append("]").toString(), "198.51.100.99"))
                .as("the request itself is admitted and answered like every other: a batch cut "
                    + "short is not a refusal, and there is nothing a report sender is entitled "
                    + "to know")
                .isEqualTo(204);

        assertThat(logged.list)
                .as("""
                    A BUDGET OF 5 A MINUTE MUST MEAN 5 LINES A MINUTE.

                    Spent once per REQUEST, this one body buys ten - and at the shipped defaults \
                    the ~19 minimal reports that fit a 16 KB body turned an instance ceiling of \
                    600 into ~12,000 lines a minute, i.e. the megabytes a minute the class javadoc \
                    says one unbounded field would have cost. There are no unbounded fields left; \
                    the multiplication was in the accounting.""")
                .hasSize(5);
        assertThat(dropped("budget") - dropsBefore)
                .as("and the five that were not written are counted, because a sink that "
                    + "discards silently is a sink whose silence cannot be read")
                .isEqualTo(5.0);
    }

    /**
     * The other half of the same claim: a batch cannot be laundered into free lines by splitting it
     * across requests either, and the first line of each request is the admission token the filter
     * already charged rather than a second free one.
     */
    @Test
    void theSixthLineIsRefusedWhetherItArrivesAloneOrInABatch() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(status(REPORT, "198.51.100.98")).isEqualTo(204);
        }

        assertThat(status(REPORT, "198.51.100.98"))
                .as("five single-report requests spend five tokens, so the sixth request is "
                    + "refused by the filter exactly as before — per-line charging did not make "
                    + "the ordinary report-uri case cheaper or dearer")
                .isEqualTo(429);
    }

    private int status(String body, String ip) throws Exception {
        return mockMvc.perform(post(ContentSecurityPolicy.REPORT_PATH)
                        .with(request -> { request.setRemoteAddr(ip); return request; })
                        .contentType("application/csp-report")
                        .content(body))
                .andReturn().getResponse().getStatus();
    }

    private double dropped(String reason) {
        var counter = meterRegistry.find("hamstrack.csp.reports_dropped")
                .tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }
}
