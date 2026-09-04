package com.hamstrack.notification;

import com.hamstrack.notification.repository.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.repository.Query;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>HD-135 AC-8 — the finder set on {@code notifications} is sealed.</strong>
 *
 * <p>The argument for leaving a departed member's rows in place instead of purging them
 * (§4.1) rests entirely on the read predicate being unforgettable. A promise that holds
 * only while every future contributor remembers it is not a control, so the seal is
 * structural and this test is what makes it so. It has three layers, and each one closes a
 * way of reading the table that the other two do not see:
 *
 * <ol>
 *   <li>the interface inherits nothing — a supertype is how an unfiltered
 *       {@code findById}/{@code findAll} arrives without appearing in any diff;</li>
 *   <li>every method it exposes carries both halves of the predicate — <em>whose</em> row
 *       and <em>which tenant</em>, because a query with only one half still discloses;</li>
 *   <li>nothing outside the repository reads the table at all — a seal on one interface
 *       says nothing about a {@code @Query} written in another file.</li>
 * </ol>
 *
 * <p>No Spring context — reflection over the interface and a read of the source tree are the
 * whole test, so it costs nothing and fails at the moment the method is added rather than at
 * the moment a user notices.
 */
class NotificationFinderSealTest {

    /**
     * The checklist, and it is deliberately the failure message rather than a comment: the
     * reader who trips this is a contributor adding a finder, and what they need is the
     * reason, not the rule.
     */
    private static final String CHECKLIST = """
            A finder on this table needs BOTH halves of the predicate. Without the membership \
            join it returns rows from workspaces the reader has left; without the ownership \
            term it returns other members' inboxes in the workspaces they share — the same \
            denormalised excerpts, addressed to somebody else. Neither half implies the other \
            — see docs/design/notification-workspace-scoping-proposal.md §4.4.

            A notification's title and body are denormalised copies of workspace content, \
            written into the row at delivery time, so a row that reaches a caller has already \
            disclosed and there is nothing left to redact downstream. Spell out both:

                WHERE n.user = :user
                  AND n.workspace.id IN (
                        SELECT m.workspace.id FROM WorkspaceMember m WHERE m.user = :user)

            (NotificationRepository.VISIBLE holds the second half — concatenate it rather than \
            retyping it.)
            Offending method(s): """;

    /** Writes, not reads: a producer is handed the row it is writing. */
    private static final java.util.Set<String> WRITES = java.util.Set.of("save");

    /**
     * Both halves of the rule, as they appear in a whitespace-normalised JPQL string. The
     * ownership terms are listed separately from the tenancy ones on purpose: a query can
     * satisfy either set alone and still hand a caller content they may not read.
     */
    private static final java.util.List<String> REQUIRED = java.util.List.of(
            "n.user = :user",              // whose row
            "n.workspace.id IN",           // which tenant
            "FROM WorkspaceMember m",      // resolved against membership, now
            "m.user = :user");             // …the SAME user, not an unbound one

    /**
     * <strong>{@code getMethods()}, not {@code getDeclaredMethods()}</strong>, and that is the
     * difference between a seal and a formality: declared methods are blind to everything a
     * supertype contributes, so a repository that inherited {@code findById} would present an
     * empty list here and pass. Together with
     * {@link #theRepositoryInheritsNoFindersAtAll()} — which stops the supertype arriving in
     * the first place — the two make an inherited finder impossible AND visible.
     */
    @Test
    void everyFinderCarriesBothHalvesOfThePredicate() {
        // A set, because one inherited supertype contributes the same overloaded name five
        // times over and a reader drowning in `findAll` five times reads none of them.
        var offenders = new LinkedHashSet<String>();
        for (Method m : NotificationRepository.class.getMethods()) {
            if (m.isSynthetic() || m.getDeclaringClass() == Object.class
                || WRITES.contains(m.getName())) continue;
            var query = m.getAnnotation(Query.class);
            if (query == null) {
                // A derived query (findAllByUser…) names no predicate it did not spell out,
                // so it cannot carry these: the seal is that there are none. An INHERITED
                // method has no @Query either, which is exactly why this loop must see it.
                offenders.add(m.getName() + " (declared by " + m.getDeclaringClass().getSimpleName()
                                          + ", no @Query at all — neither a derived query nor an "
                                          + "inherited CRUD method can express the predicate)");
                continue;
            }
            var jpql = query.value().replaceAll("\\s+", " ");
            var missing = REQUIRED.stream().filter(term -> !jpql.contains(term)).toList();
            if (!missing.isEmpty()) {
                offenders.add(m.getName() + " (missing: " + missing + ")");
            }
        }
        assertThat(offenders).as("%s", CHECKLIST + offenders).isEmpty();
    }

    /**
     * The layer a reviewer cannot enforce by reading a diff: a supertype hands this interface
     * {@code findById}, {@code findAll} and friends — unfiltered by construction — and
     * {@code notificationRepository.findById(id)} then compiles, looks like an ordinary lookup
     * in review, and reads straight past the membership rule on the one path that answers with
     * the full DTO. {@code RoleRepository} refuses the same inheritance for the same reason.
     *
     * <p><strong>The assertion is the whole supertype list, not the absence of one name</strong>,
     * because naming one leaves every sibling open: {@code CrudRepository},
     * {@code ListCrudRepository} and {@code PagingAndSortingRepository} each carry an
     * unfiltered {@code findById}, and {@code JpaSpecificationExecutor} — not a subinterface of
     * {@code JpaRepository} at all, so a check phrased about {@code JpaRepository} never sees
     * it — carries {@code findAll(Specification)}, which reads whatever predicate the caller
     * felt like passing. A custom fragment interface is the same hole wearing a project name.
     */
    @Test
    void theRepositoryInheritsNoFindersAtAll() {
        var supertypes = NotificationRepository.class.getInterfaces();
        assertThat(supertypes.length == 1 && supertypes[0] == Repository.class)
                .withFailMessage(() -> """
                NotificationRepository now extends %s. Spring Data's marker interface \
                `Repository` is the only supertype allowed here: every other one — \
                JpaRepository, CrudRepository, ListCrudRepository, PagingAndSortingRepository, \
                JpaSpecificationExecutor, or a custom fragment — contributes at least one \
                finder that is unfiltered by construction, on the single interface whose whole \
                contract is that no unfiltered finder exists. Re-declare the handful of methods \
                actually needed (Spring Data's "selectively expose CRUD methods" pattern, as \
                RoleRepository does) instead of inheriting twenty that are not.\
                """.formatted(Arrays.toString(supertypes)))
                .isTrue();
    }

    // ================================================================ the table, not the interface

    /** The one file allowed to name the table. */
    private static final Path SEALED = Path.of("src", "main", "java", "com", "hamstrack",
            "notification", "repository", "NotificationRepository.java");

    private static final Path SOURCE_ROOT = Path.of("src", "main", "java");

    /**
     * {@code FROM}/{@code UPDATE}/{@code DELETE FROM}/{@code JOIN}/{@code INTO} against either
     * spelling — the entity {@code Notification} for JPQL, the table {@code notifications} for
     * native SQL. Case-insensitive, because JPQL keywords are.
     */
    private static final Pattern READS_THE_TABLE = Pattern.compile(
            "\\b(from|update|join|into)\\s+notifications?\\b", Pattern.CASE_INSENSITIVE);

    /**
     * <strong>The seal is on the table, not on the interface.</strong> The two tests above
     * establish that {@code NotificationRepository} exposes nothing unfiltered — and say
     * nothing whatsoever about a {@code @Query} written in some other repository, a
     * {@code createQuery("SELECT n FROM Notification n …")} in a service, or a
     * {@code jdbcTemplate} call in a digest job. Each of those reads the same denormalised
     * excerpts with none of the predicate, and each would leave both layers green.
     *
     * <p>So the assertion is phrased about the table: exactly one file in {@code src/main/java}
     * may name it as a query target, and it is the one whose every method is checked above.
     * A future reader with a legitimate need — a digest, a mute list, an admin cleanup — adds
     * it to {@code NotificationRepository} carrying the predicate, or, if it genuinely must
     * live elsewhere, edits this test deliberately and explains why in the same diff. What
     * must not happen is that it lands unnoticed.
     */
    @Test
    void nothingOutsideTheSealedRepositoryQueriesTheTable() throws IOException {
        var offenders = new ArrayList<String>();
        try (Stream<Path> tree = Files.walk(SOURCE_ROOT)) {
            for (Path file : tree.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (file.equals(SEALED)) continue;
                var source = Files.readString(file, StandardCharsets.UTF_8);
                var matcher = READS_THE_TABLE.matcher(source);
                while (matcher.find()) {
                    offenders.add(SOURCE_ROOT.relativize(file) + " → \"" + matcher.group() + "\"");
                }
            }
        }
        assertThat(offenders)
                .as("""
                A query outside NotificationRepository names the notifications table. Every read \
                of that table has to carry the membership predicate — the title and body are \
                denormalised copies of workspace content, so a row that reaches a caller has \
                already disclosed and nothing downstream can redact it. Move the query into \
                NotificationRepository (concatenating VISIBLE), where NotificationFinderSealTest \
                checks it, or state in the same diff why this one is exempt. Found: """
                + offenders)
                .isEmpty();
    }
}
