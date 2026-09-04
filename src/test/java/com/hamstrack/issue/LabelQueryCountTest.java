package com.hamstrack.issue;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The "no N+1" half of §3.7 / §4.6: a board or backlog page of labelled issues must
 * cost a <strong>constant</strong> number of SQL statements, whatever the page size —
 * {@code LabelService.labelsByIssue} loads the whole page's labels in ONE query keyed
 * by issue id (mirroring {@code FieldValueService.valuesByIssue}), never one query per
 * card.
 *
 * <p>There was no query-count harness in this project, so this test adds the smallest
 * honest one: Hibernate's own statement counter, enabled for this context only via
 * {@code hibernate.generate_statistics}. The assertion is a <em>comparison between two
 * page sizes</em>, not a magic absolute number — an absolute would be a brittle
 * tripwire that fires on every unrelated query change, whereas equality across sizes is
 * exactly the property "no per-row query" means.
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=false",
        "app.demo.seed-on-first-login=false",
        "seed.admin.email=",
        "app.board.max-issues=200",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@AutoConfigureMockMvc
class LabelQueryCountTest extends LabelTestBase {

    @Autowired EntityManagerFactory entityManagerFactory;

    @Test
    void boardAndBacklogQueryCountIsIndependentOfPageSize() throws Exception {
        var small = projectWithLabelledIssues(3);
        var large = projectWithLabelledIssues(40);

        // Warm both paths once (first execution also compiles/populates caches).
        board(small, null);
        board(large, null);

        long smallBoard = countStatements(() -> board(small, null));
        long largeBoard = countStatements(() -> board(large, null));
        assertThat(smallBoard)
                .as("board is N+1: " + smallBoard + " statements for 3 issues vs "
                  + largeBoard + " for 40")
                .isEqualTo(largeBoard);

        long smallBacklog = countStatements(() -> board(small, "?size=100"));
        long largeBacklog = countStatements(() -> board(large, "?size=100"));
        assertThat(smallBacklog)
                .as("backlog is N+1: " + smallBacklog + " statements for 3 issues vs "
                  + largeBacklog + " for 40")
                .isEqualTo(largeBacklog);

        // Sanity: the labels really are there (an empty board would pass trivially).
        var node = board(large, null);
        assertThat(node.get("issues")).as("expected all 40 issues on the board").hasSize(40);
        for (var issue : node.get("issues")) {
            assertThat(issue.get("labels").size()).as("every issue must carry labels").isGreaterThanOrEqualTo(1);
        }
    }

    /** A fresh project whose every issue carries two of the workspace's labels. */
    private Ctx projectWithLabelledIssues(int count) throws Exception {
        var ctx = newProject();
        var a = createLabel(ctx, "alpha");
        var b = createLabel(ctx, "beta");
        for (int i = 0; i < count; i++) {
            createIssue(ctx, "issue " + i, labelIdsJson(a, b));
        }
        return ctx;
    }

    private long countStatements(ThrowingRunnable body) throws Exception {
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

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
