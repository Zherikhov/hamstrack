package com.hamstrack.common.mail;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>Every mailer is reached from an {@code AfterCommit}-registered effect, or is exempt with a
 * written reason</strong> (HD-181).
 *
 * <h2>Why a seal and not a comment</h2>
 * The place this rule breaks is not the three call sites the ticket fixed — it is the
 * <strong>fourth</strong>. A mailer added next quarter, called inline from the middle of a
 * {@code @Transactional} service, reintroduces the whole bug with no diff that looks like a
 * regression: the code will read exactly like the code that was there before HD-181, and every test
 * in this ticket will stay green because none of them knows the new mailer exists. That is the shape
 * this project already seals twice — {@code ThrottleCoverageTest} for the throttled path set,
 * {@code MailThrottleCoverageTest} for the mail axis of the same idea — with a test whose failure
 * message is the propagation checklist, because a comment cannot fire.
 *
 * <p>It is worth its weight for one further reason specific to this rule: <strong>the failure is
 * silent in both directions.</strong> A send published before the commit works perfectly until the
 * transaction that carries it happens to roll back, which is rare, load-dependent, and invisible in
 * any test that does not deliberately force it. Nothing goes red; a stranger just receives a link
 * that resolves to nothing.
 *
 * <h2>What is asserted, and the honest limits of it</h2>
 * The mailer names are <em>reflected</em> off {@link MailService}, not listed — a new public
 * {@code send*} method is covered on the commit that adds it, which is the property that makes this
 * more than bookkeeping. Their call sites are then found by scanning {@code src/main/java} with
 * comments and string literals blanked out, and each one must sit inside the argument list of an
 * {@code AfterCommit.run(...)}.
 *
 * <p>This is a <strong>syntactic</strong> check and says so: it proves the call is lexically inside
 * a registered effect, not that the effect is registered on the right transaction. A call counts
 * when its receiver is something the same file declares as a {@code MailService} — matching the
 * method name alone was wrong in the direction that matters, because {@code AuthService} has a
 * private helper called {@code sendVerificationEmail} and its {@code this::} reference was reported
 * as an offender. What the scan therefore cannot see is a mailer invoked through an interface, a
 * method reference stored in a field, or a helper three frames down — so it is a floor, not a
 * ceiling. {@link #everyMailerHasACallSiteThisScanCanSee}
 * is what keeps that floor from silently dropping to zero: a mailer whose call sites this scanner
 * stops finding is a mailer this seal has stopped covering, and it fails rather than shrugging.
 */
class MailerAfterCommitCoverageTest {

    /**
     * Call sites that may invoke a mailer without registering it on the commit, keyed
     * {@code Class.mailer} and carrying the reason.
     *
     * <p><strong>Empty, and an entry here is a decision.</strong> The question an entry has to
     * answer is not "is a rollback likely here" — every rollback is rare until it happens, and the
     * causes include ones no author chose (a constraint violation, a late refusal, a statement
     * cancelled at the bound {@code BoundedJpaTransactionManager} applies). It is: <em>what does the
     * recipient hold if this transaction rolls back after the send?</em> "This path has no
     * transaction" is not a reason to be here — {@code AfterCommit.run} already runs inline in that
     * case, so the call site costs nothing and stays correct when a caller later wraps it in one.
     */
    private static final Map<String, String> EXEMPT_CALL_SITES = Map.of();

    /**
     * Mailers this scan finds no call site for. <strong>Empty.</strong> An entry means the seal
     * covers that mailer's name and nothing else, so it needs a reason that says how the rule is
     * enforced instead — "it is called only from tests" or "it is called through an interface this
     * scanner cannot follow, and the call site is X".
     */
    private static final Set<String> MAILERS_WITH_NO_CALL_SITE = Set.of();

    /** <strong>The checklist, printed by the failure that needs it.</strong> */
    private static final String PROPAGATION_CHECKLIST = """

            A MAILER CALLED WITHOUT BEING REGISTERED ON THE COMMIT (HD-181).

            An email cannot be unsent. A send published from inside a live transaction is published \
            before the database has kept its half, so ANY rollback taken afterwards — a constraint \
            violation, a late 409, a statement cancelled at the bound BoundedJpaTransactionManager \
            applies — leaves the recipient holding a link whose row never existed. Handing the send \
            to the mail pool does not help and never did: the pool used to be bounded with a \
            caller-runs policy, so under load the dispatch became a synchronous send on the calling \
            thread, inside the transaction and inside whatever locks it held. HD-208 removed that \
            branch and it changes nothing here — an ordinary asynchronous dispatch published BEFORE \
            the commit is still published before the database has kept its half, and the executor \
            has no idea a rollback happened.

            Do this at the call site:

              1. Read everything the send needs into LOCALS first. The effect must not touch the \
                 EntityManager at all — not a lazy read (which works while the context is still \
                 bound and throws where nothing is, and escapes the statement timeout wherever it \
                 does work) and not a write (which joins an already-committed transaction and is \
                 discarded WITHOUT THROWING — see FailedEmailWriter).
              2. Wrap the send: AfterCommit.run(description, () -> mailService.sendX(...)).
              3. The DESCRIPTION is written verbatim into a log that is shipped and kept, so it \
                 carries the mail kind and MailAddresses.domainOf(recipient) — never the address. \
                 It is the entire record if the dispatch is lost, so name the row an operator would \
                 have to find (workspace id, and so on).

            Then check the two neighbouring rules a new mailer also lands on:

              - MailThrottleCoverageTest — a new EmailType is recipient-throttled or exempt with a \
                written reason.
              - MailService.isCritical — account-critical mail retries and dead-letters; \
                best-effort mail is dropped with a log line. A new mailer is one or the other, \
                deliberately.

            If a call site genuinely must not defer, add it to EXEMPT_CALL_SITES above with a \
            reason that answers: what does the recipient hold if this transaction rolls back?
            """;

    /** The scanner deliberately skips the declaring class — see {@link #mailerNames()}. */
    private static final Path MAIL_SERVICE =
            Path.of("src", "main", "java", "com", "hamstrack", "common", "mail", "MailService.java");

    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");

    // ================================================================ the seal

    @Test
    void everyMailerCallSiteIsInsideAnAfterCommitEffect() {
        var offenders = callSites().stream()
                .filter(site -> !site.insideAfterCommit())
                .filter(site -> !EXEMPT_CALL_SITES.containsKey(site.key()))
                .toList();

        assertThat(offenders)
                .as("these call sites hand an email to the mail executor from inside a live "
                    + "transaction, so a rollback taken afterwards delivers a link to a row that "
                    + "never existed." + PROPAGATION_CHECKLIST)
                .isEmpty();
    }

    /**
     * The floor under the seal above. Without it, a mailer this scanner cannot see is silently
     * uncovered and the test still passes — the failure mode that makes a coverage test worse than
     * none, because it reports safety it is not measuring.
     */
    @Test
    void everyMailerHasACallSiteThisScanCanSee() {
        var found = callSites().stream().map(CallSite::mailer).collect(java.util.stream.Collectors.toSet());
        var unseen = new LinkedHashSet<>(mailerNames());
        unseen.removeAll(found);
        unseen.removeAll(MAILERS_WITH_NO_CALL_SITE);

        assertThat(unseen)
                .as("this scan finds no call site for these mailers in src/main/java, so the seal "
                    + "above covers them vacuously. Either they are unreachable (delete them), or "
                    + "they are reached in a way this scanner cannot follow — a method reference "
                    + "held in a field, a call through an interface, a helper several frames down "
                    + "— in which case the ordering rule for them is enforced by nothing at all. "
                    + "Name them in MAILERS_WITH_NO_CALL_SITE with the reason and how the rule is "
                    + "kept for them instead." + PROPAGATION_CHECKLIST)
                .isEmpty();
    }

    /**
     * The premise of both tests above: this file must be run from the project root, against a
     * {@link MailService} that still declares its mailers as public {@code send*} methods. Both are
     * assumptions that fail silently — a moved source root makes the scan find nothing, and a
     * renamed convention makes the reflection find nothing — and either would leave the seals
     * passing over an empty set.
     */
    @Test
    void theScannerIsActuallyLookingAtSomething() {
        assertThat(MAIN_SOURCES)
                .as("src/main/java was not found from the working directory, so nothing was "
                    + "scanned and both seals above are guarding an empty set")
                .isDirectory();
        assertThat(mailerNames())
                .as("no public send* method was found on MailService, so this file has no handle "
                    + "on the set of mailers — the class moved or the naming convention changed")
                .isNotEmpty();
        assertThat(callSites())
                .as("no mailer call site was found anywhere in src/main/java. Either mail is no "
                    + "longer sent, or this scanner has stopped being able to see how it is")
                .isNotEmpty();
    }

    // ================================================================ scanner

    /**
     * One invocation of a mailer in main sources.
     *
     * @param insideAfterCommit whether it sits lexically inside the argument list of an
     *                          {@code AfterCommit.run(...)}
     */
    private record CallSite(String owner, String mailer, int line, boolean insideAfterCommit) {

        private String key() {
            return owner + "." + mailer;
        }

        @Override
        public String toString() {
            return owner + ".java:" + line + " calls MailService." + mailer
                   + " outside any AfterCommit.run(...)";
        }
    }

    /** Public {@code send*} methods on {@link MailService} — reflected, never listed. */
    private static List<String> mailerNames() {
        return Arrays.stream(MailService.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .filter(m -> !m.isSynthetic())
                .filter(m -> m.getName().startsWith("send"))
                .map(java.lang.reflect.Method::getName)
                .distinct()
                .toList();
    }

    private static List<CallSite> callSites() {
        var mailers = mailerNames();
        var sites = new ArrayList<CallSite>();
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            files.filter(p -> p.toString().endsWith(".java"))
                    // The declaring class is skipped: a mailer's own declaration is not a call, and
                    // scanning it would make any future mailer named plainly (`send`) collide with
                    // MailService's private helpers and its mailSender.send(...) calls.
                    .filter(p -> !p.equals(MAIL_SERVICE))
                    .sorted()
                    .forEach(p -> sites.addAll(callSitesIn(p, mailers)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return sites;
    }

    private static List<CallSite> callSitesIn(Path file, List<String> mailers) {
        String source;
        try {
            source = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var code = blankCommentsAndLiterals(source);
        var owner = file.getFileName().toString().replace(".java", "");
        var found = new ArrayList<CallSite>();

        // The receiver has to be something this file declares as a MailService. Matching a bare
        // `.sendVerificationEmail(` would be wrong in the direction that matters — AuthService has
        // a PRIVATE helper of exactly that name, and `this::sendVerificationEmail` was reported as
        // an unregistered call site until this line existed. A false alarm in a seal is not a safe
        // failure: it is what gets the seal weakened.
        var receivers = mailServiceReceiversIn(code);
        if (receivers.isEmpty()) {
            return found;
        }

        // Depth stack: one entry per open paren, true when that paren opened an AfterCommit.run
        // argument list. A call is "registered" when any enclosing paren is such a one.
        Deque<Boolean> enclosing = new ArrayDeque<>();
        int line = 1;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '\n') {
                line++;
            } else if (c == '(') {
                enclosing.push(endsWithAfterCommitRun(code, i));
            } else if (c == ')') {
                enclosing.poll();
            } else if (c == '.' || c == ':') {
                for (var mailer : mailers) {
                    boolean call = code.startsWith("." + mailer + "(", i)
                                   || code.startsWith("::" + mailer, i);
                    if (call && receivers.contains(receiverBefore(code, i))) {
                        found.add(new CallSite(owner, mailer, line, enclosing.contains(Boolean.TRUE)));
                    }
                }
            }
        }
        return found;
    }

    /** Names this file declares as a {@link MailService} — the field, parameter or local. */
    private static Set<String> mailServiceReceiversIn(String code) {
        var receivers = new LinkedHashSet<String>();
        var declaration = java.util.regex.Pattern.compile("(?<![\\w.])MailService\\s+(\\w+)");
        var matcher = declaration.matcher(code);
        while (matcher.find()) {
            receivers.add(matcher.group(1));
        }
        return receivers;
    }

    /** The identifier immediately to the left of the {@code .} or {@code ::} at {@code index}. */
    private static String receiverBefore(String code, int index) {
        int end = index;
        int start = end;
        while (start > 0 && Character.isJavaIdentifierPart(code.charAt(start - 1))) {
            start--;
        }
        return code.substring(start, end);
    }

    /** True when the {@code (} at {@code parenIndex} is the one opening {@code AfterCommit.run(}. */
    private static boolean endsWithAfterCommitRun(String code, int parenIndex) {
        int end = parenIndex;
        while (end > 0 && Character.isWhitespace(code.charAt(end - 1))) {
            end--;
        }
        return code.startsWith("AfterCommit.run", Math.max(0, end - "AfterCommit.run".length()))
               && end >= "AfterCommit.run".length();
    }

    /**
     * Replaces the contents of comments, string literals, text blocks and char literals with spaces,
     * keeping every index and line number intact.
     *
     * <p>Necessary rather than fastidious: this package's prose mentions {@code AfterCommit.run} and
     * the mailer names constantly, and a scanner that read javadoc would find a "registered" call
     * site in a paragraph explaining why one is needed.
     */
    private static String blankCommentsAndLiterals(String source) {
        var out = new StringBuilder(source);
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                i = blankUntil(out, source, i, "\n", false);
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                i = blankUntil(out, source, i + 2, "*/", false);
            } else if (source.startsWith("\"\"\"", i)) {
                i = blankUntil(out, source, i + 3, "\"\"\"", true);
            } else if (c == '"') {
                i = blankUntil(out, source, i + 1, "\"", true);
            } else if (c == '\'') {
                i = blankUntil(out, source, i + 1, "'", true);
            } else {
                i++;
            }
        }
        return out.toString();
    }

    /**
     * Blanks from {@code from} to the end of {@code terminator}, honouring backslash escapes when
     * {@code escapes} is set. Newlines survive so line numbers stay true.
     *
     * @return the index just past the terminator
     */
    private static int blankUntil(StringBuilder out, String source, int from, String terminator,
                                  boolean escapes) {
        int i = from;
        int n = source.length();
        while (i < n && !source.startsWith(terminator, i)) {
            if (escapes && source.charAt(i) == '\\' && i + 1 < n) {
                blank(out, i);
                blank(out, i + 1);
                i += 2;
                continue;
            }
            blank(out, i);
            i++;
        }
        int end = Math.min(n, i + terminator.length());
        for (int j = i; j < end; j++) {
            blank(out, j);
        }
        return end;
    }

    private static void blank(StringBuilder out, int index) {
        if (out.charAt(index) != '\n') {
            out.setCharAt(index, ' ');
        }
    }
}
