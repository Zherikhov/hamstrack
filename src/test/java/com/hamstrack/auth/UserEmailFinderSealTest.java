package com.hamstrack.auth;

import com.hamstrack.auth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>HD-167 AC 14 — the finders on {@code users.email} are sealed, and the seal is about
 * WHICH FUNCTION they fold with.</strong>
 *
 * <p>The rule {@code UserRepository} states is the generating property rather than a list, because
 * a list goes stale one caller before the rule does:
 *
 * <blockquote><strong>A check that can only REFUSE folds. A lookup that RESOLVES an identity
 * compares exactly.</strong></blockquote>
 *
 * <p>What this file adds is the half that rule cannot express on its own: <em>a folding check must
 * fold with the same function the index does.</em> {@code users_email_lower_uk} is built on
 * PostgreSQL's {@code lower()}. Spring Data's derived {@code IgnoreCase} keyword generates
 * {@code upper(…)}, and {@code upper} is not the inverse of {@code lower} on every input — so
 * {@code existsByEmailIgnoreCase} would ask a <strong>different question than the index answers</strong>,
 * which is the exact defect {@code existsByFoldedEmail} was written to prevent, arriving in a new
 * spelling and in a shorter, more obvious name that autocomplete offers first.
 *
 * <p><strong>The failure mode is silent and it is not a 500.</strong> Where the two folds
 * disagree, an exists-check that says "free" while the index says "taken" puts an ordinary signup
 * through a <em>doomed</em> INSERT — answered 409 today only because {@code EmailUniqueness}
 * translates it, and answered 500 the day that catch stops matching (a renamed constraint, a new
 * writer that forgets it). The refusal must not depend on the translation being right, and this is
 * the structural half of making that true.
 *
 * <p><strong>A near miss worth naming, because it looks like a counter-example.</strong>
 * {@code WorkspaceInviteRepository.findByEmailIgnoreCaseAndAcceptedAtIsNull…} is <em>named</em>
 * {@code IgnoreCase} and is backed by an explicit {@code @Query} with {@code lower()}. The name
 * lies. It is not a pattern to copy, and this seal is scoped to {@code UserRepository} rather than
 * written repository-wide precisely so it does not have to carry an exception list that would
 * teach the wrong lesson.
 *
 * <p>No Spring context — reflection over the interface plus a read of the source tree, so it costs
 * nothing and fails at the moment the method is added rather than at the moment two people share
 * one account.
 */
class UserEmailFinderSealTest {

    private static final Path MAIN = Path.of("src", "main", "java");

    /**
     * The checklist, and it is deliberately the failure message rather than a comment: the reader
     * who trips this is a contributor adding a finder, and what they need is the reason.
     */
    private static final String CHECKLIST = """

            A finder on users.email folds with PostgreSQL's lower() or it does not fold at all.

            Spring Data's derived IgnoreCase keyword generates upper(), and upper is not the \
            inverse of lower on every input — so a derived finder asks a DIFFERENT question than \
            users_email_lower_uk (V23) answers. Where they disagree the check says "free" while \
            the index says "taken", and an ordinary signup runs a doomed INSERT: a 409 only for \
            as long as EmailUniqueness keeps matching the constraint name, and a 500 after that.

            Write it as an explicit @Query instead:

                @Query("select ... from User u where lower(u.email) = lower(:email)")

            …and then decide which kind of question it is. A check that can only REFUSE folds \
            (existsByFoldedEmail, findByFoldedEmail). A lookup that RESOLVES an identity — login, \
            forgotPassword, resendVerification, the already-a-member check — compares EXACTLY, \
            because an extra match on a refusal declines someone who was entitled, while an extra \
            match on a resolution admits the wrong person.
            """;

    /**
     * <strong>No derived {@code IgnoreCase} finder on this interface</strong> (AC 14).
     *
     * <p>Stated over every method rather than over the two that exist today, which is the whole
     * point of a seal: the defect this prevents arrives as a <em>new</em> method, in a diff that
     * looks like a convenience.
     */
    @Test
    void noFinderOnUsersFoldsWithSpringDatasUpper() {
        var derived = Arrays.stream(UserRepository.class.getDeclaredMethods())
                .filter(m -> m.getName().contains("IgnoreCase"))
                .map(Method::getName)
                .toList();

        assertThat(derived)
                .withFailMessage("UserRepository declares %s.%s", derived, CHECKLIST)
                .isEmpty();
    }

    /**
     * <strong>And the two that DO fold, fold in the index's own expression.</strong>
     *
     * <p>The seal above is an absence, and an absence is satisfied by deleting the folded finders
     * altogether. This is the presence that goes with it: both write-side questions must still be
     * asked with {@code lower(…)}, on both sides of the comparison, which is what makes the
     * application's check and the database's guarantee unable to disagree — for any input, under
     * any {@code LC_CTYPE}, on any collation provider.
     */
    @Test
    void bothWriteSideChecksAskTheQuestionInTheIndexsExpression() {
        for (var name : List.of("existsByFoldedEmail", "findByFoldedEmail")) {
            var query = queryOf(name);
            assertThat(query)
                    .withFailMessage("%s is missing, or carries no @Query and was therefore derived by Spring Data.%s",
                            name, CHECKLIST)
                    .isNotNull();
            assertThat(query.toLowerCase(Locale.ROOT))
                    .withFailMessage("%s must fold BOTH sides with lower(): %s%s",
                            name, query, CHECKLIST)
                    .contains("lower(")
                    .doesNotContain("upper(");
        }
    }

    /**
     * <strong>The exact-match existence check has no production caller, and that is a claim worth
     * sealing rather than merely writing down.</strong>
     *
     * <p>{@code existsByEmail} sits one line above the folded one, with the shorter and more
     * obvious name — which is what autocomplete offers first — and the defect it reintroduces on a
     * write path is the one V23's whole translation layer exists to avoid. It is kept because
     * tests assert on it, where an exact check is the correct question ("is this the row we
     * wrote?").
     *
     * <p>If a genuine <em>read-side</em> caller is ever added, this test is the place that says so
     * out loud: change it together with the sentence on {@link UserRepository} that claims there
     * are none, rather than letting a count go stale one entry before the list does.
     */
    @Test
    void nothingInProductionAsksTheExactExistenceQuestion() throws IOException {
        var callers = new ArrayList<String>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (var file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (file.endsWith(Path.of("UserRepository.java"))) continue;
                var source = Files.readString(file, StandardCharsets.UTF_8);
                for (var line : source.split("\n")) {
                    if (line.contains(".existsByEmail(")) {
                        callers.add(file + ": " + line.strip());
                    }
                }
            }
        }

        assertThat(callers)
                .withFailMessage("""

                        %s calls UserRepository.existsByEmail — an EXACT existence check.

                        On a WRITE path that is the defect: it says "free" for an address a \
                        stored mixed-case row already occupies, so the INSERT runs and is refused \
                        by users_email_lower_uk. That is a 409 only while EmailUniqueness keeps \
                        matching the constraint name, and a 500 after. Ask existsByFoldedEmail, \
                        which asks the question in the expression the index answers it in.

                        On a genuine READ path it may be correct — in which case update this test \
                        AND the sentence on UserRepository that says there is no production \
                        caller, in the same commit. A count goes stale one entry before the list \
                        does.""", callers)
                .isEmpty();
    }

    private static String queryOf(String method) {
        return Arrays.stream(UserRepository.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(method))
                .findFirst()
                .map(m -> m.getAnnotation(Query.class))
                .map(Query::value)
                .orElse(null);
    }
}
