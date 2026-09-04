package com.hamstrack.common.security;

import com.hamstrack.common.config.JwtProperties;
import com.hamstrack.common.seed.DataSeeder;
import com.hamstrack.ops.PublishedCredentials;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * <strong>HD-200 — the application half of "no published value may be a working
 * credential".</strong>
 *
 * <p>{@code .env.prod.example} now ships its secrets empty, which stops the next install. It
 * cannot reach the ones that already happened: an instance that copied the old placeholder is
 * running today, signing every access token with a string in a public repository, and nothing
 * about it looks wrong. That is what the refusals here are for — a template edit protects
 * future readers, a startup refusal protects existing installations.
 *
 * <p>The trap being closed is that the placeholder <em>passed</em> the length check. It was
 * 34 bytes and the check refuses what is under 32, so the guard fired on absence and a
 * placeholder is the one thing that is not absent.
 *
 * <p><strong>The class is named after a member; the scan in it is about a category.</strong>
 * {@link #noPublishedCredentialIsAcceptedUnlessItSaysWhatItIs()} covers every
 * credential-shaped variable this repository publishes, not {@code JWT_SECRET}. It had to
 * be widened once already: written as {@code grep JWT_SECRET}, it sat beside
 * {@code DB_MONITOR_PASSWORD=a-strong-password} — a {@code pg_monitor} login on the
 * production database, published, under a "recommended for prod" heading — and reported
 * nothing, because the claim it was making was about one variable and the lesson was about
 * all of them. The rule itself lives in {@link PublishedCredentials}, shared with the
 * template guard so the two cannot disagree about what a credential is.
 *
 * <p>The template's side of the same rule — that no template ships a value satisfying any
 * guard, and that no guarded variable is missing from it — is
 * {@code com.hamstrack.ops.EnvTemplateGuardTest}.
 */
class JwtSecretValidationTest {

    private static final Path TEMPLATE = Path.of(".env.prod.example");
    private static final Path REPO_ROOT = Path.of(".");

    private static final Set<String> SKIPPED_DIRECTORIES =
            Set.of(".git", ".gstack", ".idea", ".local", "target", "node_modules", "data", "logs");

    /**
     * Assembled rather than written out because the scan below reads this very file: a
     * literal assignment here would be picked up as a published value.
     */
    private static final String TEMPLATE_KEY = "JWT_SECRET" + "=";

    private static final Set<String> SCANNED_EXTENSIONS =
            Set.of("md", "yml", "yaml", "properties", "sh", "java", "txt", "example", "service", "timer");

    private static void validate(String secret) {
        new JwtService(new JwtProperties(secret, Duration.ofMinutes(30), Duration.ofDays(7))).validateSecret();
    }

    @Test
    void refusesTheValueAnUneditedTemplateNowProduces() throws IOException {
        String fromTemplate = templateValue();
        assertThat(fromTemplate)
                .withFailMessage("The template no longer ships the signing key empty (found `%s`) — see "
                        + "EnvTemplateGuardTest for why nothing in that file may satisfy its own guard",
                        fromTemplate)
                .isEmpty();

        // Copied unedited, Compose passes the empty string through: the property resolves,
        // so what refuses it is the length check and not an unresolvable placeholder.
        assertThatIllegalStateException()
                .isThrownBy(() -> validate(fromTemplate))
                .withMessageContaining("must be at least 32 bytes")
                .withMessageContaining("current value is 0 bytes")
                .withMessageContaining("openssl rand -base64 48");
    }

    @Test
    void refusesAnAbsentOrShortSecret() {
        assertThatIllegalStateException()
                .isThrownBy(() -> validate(null))
                .withMessageContaining("current value is missing");
        assertThatIllegalStateException()
                .isThrownBy(() -> validate("31-bytes-is-one-short-of-enough"))
                .withMessageContaining("current value is 31 bytes");
    }

    /**
     * The value is refused <em>by name</em>, and the message has to say why: an operator who
     * upgrades into this refusal is being told their instance was already compromised, not
     * that the release broke. A message that only said "invalid secret" would read as a bug
     * and be worked around.
     */
    @Test
    void refusesThePlaceholdersThisProjectHasPublished() {
        for (String published : JwtService.PUBLISHED_PLACEHOLDERS) {
            assertThat(published.getBytes(StandardCharsets.UTF_8).length)
                    .withFailMessage("`%s` is under 32 bytes, so this case proves nothing beyond the length "
                            + "check — the whole point is that the published placeholders were long enough "
                            + "to pass it", published)
                    .isGreaterThanOrEqualTo(32);

            assertThatIllegalStateException()
                    .isThrownBy(() -> validate(published))
                    .withMessageContaining("published in Hamstrack's own documentation")
                    .withMessageContaining("openssl rand -base64 48");
        }
    }

    /**
     * <strong>The remediation sentence has to be true, and was not.</strong> Until HD-200
     * this refusal ended "changing it signs out every existing session, which in this case is
     * the intended outcome". Rotation does no such thing: refresh tokens are opaque random
     * values stored hashed, independent of {@code jwt.secret}, and {@code /api/auth/refresh}
     * is {@code permitAll} — so the SPA takes one 401 and continues under the new key.
     *
     * <p>This is the one message where that matters most. An operator reading it has just
     * learned their signing key is public; being told rotation is a complete purge means they
     * will not revoke refresh sessions, will not audit admin accounts and will not look at
     * recent password resets — the steps that actually cut an attacker who converted a forged
     * token into a durable foothold. It also overstates the cost of rotating, which is a
     * reason to defer.
     */
    @Test
    void saysWhatRotationActuallyDoes() {
        String message = refusalFor(JwtService.PUBLISHED_PLACEHOLDERS.iterator().next());

        assertThat(message)
                .withFailMessage("The refusal claims rotation ends sessions. It does not — refresh tokens "
                        + "are independent of jwt.secret, so every client silently re-issues. Saying "
                        + "otherwise tells an operator the cleanup is already done.\nMessage was: " + message)
                .doesNotContain("signs out every")
                .doesNotContain("signed out")
                .doesNotContain("invalidates every existing session");
        assertThat(message)
                .withFailMessage("The refusal must say that sessions SURVIVE rotation and name the steps "
                        + "that do not happen by themselves - revoking refresh sessions, DELETING UNUSED "
                        + "RESET/SETUP LINKS, and auditing admin accounts. `password_resets` is the one that "
                        + "went missing here while both prose copies carried it: this message said `audit ... "
                        + "recent password resets`, a thing to look at rather than a statement to run, and an "
                        + "unused admin-issued setup link is the cheapest durable foothold a forged admin "
                        + "token buys - 7-day TTL, and neither the rotation nor the refresh_tokens delete "
                        + "reaches it.\nMessage was: " + message)
                .contains("does NOT end sessions")
                .contains("refresh_tokens")
                .contains("password_resets")
                .contains("audit");
    }

    @Test
    void acceptsAGeneratedSecret() {
        var bytes = new byte[48];
        new SecureRandom().nextBytes(bytes);
        String generated = Base64.getEncoder().encodeToString(bytes);
        assertThatCode(() -> validate(generated)).doesNotThrowAnyException();
        assertThatCode(() -> validate("exactly-thirty-two-bytes-of-key!")).doesNotThrowAnyException();
    }

    /**
     * <strong>The denylist is pinned to its size, because it is one word away from becoming
     * a weak-password checker.</strong> Before HD-200's fix round the failure message below
     * advertised "add it to PUBLISHED_PLACEHOLDERS" as a remedy, which makes silencing this
     * whole class a two-line edit — and a denylist that grows on "this looks weak" invites
     * "mine is not on the list" and goes stale by the week.
     *
     * <p>The standard for an entry is evidentiary and narrow: <em>this repository published
     * that exact string as production configuration, under the variable's own name</em>, so
     * an instance running it is not weakly protected but publicly signable. If you cannot
     * name the commit ({@code git log -S}), the value does not belong here.
     */
    @Test
    void thePublishedDenylistIsPinnedToItsEvidence() {
        assertThat(JwtService.PUBLISHED_PLACEHOLDERS)
                .withFailMessage("""

                        JwtService.PUBLISHED_PLACEHOLDERS changed size. That set is not a weak-secret \
                        denylist and must not become one - a list of bad-looking values invites "mine is \
                        not on the list" and is stale by the week.

                        An entry is justified only by evidence: THIS repository published that exact \
                        string as production configuration, under JWT_SECRET's own name, so every instance \
                        that copied it is publicly signable and no template edit can reach it. Name the \
                        commit (git log -S'<value>') in the review, then update this count.

                        Removing one is the same conversation in reverse: the installations that copied \
                        it do not stop existing because we stopped listing it.""")
                .hasSize(2);
    }

    /**
     * <strong>The acceptance criterion, asserted rather than remembered:</strong> no
     * credential this repository publishes is usable.
     *
     * <p>Enumerated from the tree instead of from a list, because a list of "the places we
     * put a sample secret" is exactly what goes stale — the quick-start compose in
     * {@code docs/self-hosting.md} carried a 45-byte signing key for months while the ticket
     * about the template was open, and {@code docs/observability.md} handed out a
     * {@code pg_monitor} password in SQL and in {@code .env} form on adjacent lines, agreeing
     * with each other, under a heading recommending it for production.
     *
     * <p>What counts as an assignment, and what makes one acceptable, is
     * {@link PublishedCredentials} — shared with the template guard on purpose.
     *
     * <p><strong>Scope, stated so it is not mistaken for more.</strong> This looks for the
     * ENVIRONMENT VARIABLE form, which is what every sample an operator copies uses, and it
     * reads the working tree rather than the index — an uncommitted file is about to be
     * published, and the rule may only be made stricter by one. The Spring property form
     * ({@code jwt.secret=…} in {@code application-local.properties}) is deliberately outside
     * it: that file is gitignored, so it is a developer machine's own configuration and not
     * something this repository publishes — which is the whole subject of the rule.
     */
    @Test
    void noPublishedCredentialIsAcceptedUnlessItSaysWhatItIs() throws IOException {
        var offences = new ArrayList<String>();
        var scanned = new ArrayList<Path>();
        var localDevStack = PublishedCredentials.localDevStackCredentials();

        try (Stream<Path> tree = Files.walk(REPO_ROOT)) {
            for (Path file : tree.filter(JwtSecretValidationTest::isScannable).toList()) {
                scanned.add(file);
                for (var found : PublishedCredentials.assignments(file, PublishedCredentials.read(file))) {
                    if (isOffence(found, localDevStack)) {
                        offences.add(found.describe());
                    }
                }
            }
        }

        assertThat(scanned)
                .withFailMessage("The scan read no files at all, so it proves nothing — it must run from "
                        + "the module root (working directory was %s)", REPO_ROOT.toAbsolutePath())
                .isNotEmpty();

        assertThat(offences)
                .withFailMessage("""

                        A CREDENTIAL PRINTED IN THIS REPOSITORY IS NOT A CREDENTIAL (HD-200).

                        It has happened three times, in three dialects: a 34-byte JWT_SECRET placeholder \
                        that was long enough to pass the length check, DB_PASSWORD=DB_PASSWORD seeding \
                        both the Postgres container and the app, and SEED_ADMIN_PASSWORD repeating its \
                        own name - the last of which is an ACTIVE system administrator whose email and \
                        password are both published. None of them looks wrong from the outside.

                        Ways to make this pass, in order of preference:

                          1. Ship the line EMPTY and let the guard fire. Required for anything a reader \
                        copies as PRODUCTION configuration - .env.prod.example, the quick-start compose \
                        and the restore drill in docs/self-hosting.md, the runbooks in docs/, ops/**.
                          2. Write it as an UNFILLED BLANK: <a strong password>, not a-strong-password. \
                        The angle brackets are the convention that carries the difference - one reads as \
                        something to fill in, the other as a value somebody already chose, and only one \
                        of them gets pasted into production. Use this where an operator must set the same \
                        value in two places (a CREATE ROLE and a .env line).
                          3. Name it a throwaway in its own text (dev-only-..., ci-only-..., \
                        drill-only-..., test-only-...) if a local, CI or restore-drill command genuinely \
                        needs a value that works. The label travels with the value into whatever file \
                        someone pastes it into, which a list of blessed strings does not.
                          4. REFUSE it in the application - JwtService.PUBLISHED_PLACEHOLDERS, \
                        DataSeeder.PUBLISHED_PASSWORDS - if this repository already published it as \
                        production configuration. That is the only ground that also reaches the installs \
                        which copied it, and it is not a free pass: both sets are pinned to their size by \
                        a test, and an entry needs a commit you can name (git log -S), not a hunch that a \
                        value looks weak.

                        AND WHEN THE NAME LIES - IDEMPOTENCY_KEY, SORT_KEY and PARTITION_KEY are \
                        credential-shaped and hold no secret - none of the above is about your line, and \
                        the move that suggests itself (narrow CREDENTIAL_SHAPED so it stops matching) is \
                        the one to refuse: a rule about names cannot read what a value means, and every \
                        narrowing is permanent, silent and applies to every file. Use remedy 2 instead - \
                        <any unique string> is a better sample idempotency key than an invented literal, \
                        and it costs the reader nothing. If a working literal is genuinely required, that \
                        is a review conversation and not an edit to the pattern.

                        Credentials of the local dev stack (docker-compose.yml, \
                        docker-compose.*.dev.yml AT THE REPOSITORY ROOT) are exempt outside production \
                        configuration, and the exempt VALUES are read out of those files - you cannot \
                        widen that by editing a list here, only by changing what `docker compose up` \
                        creates.

                        Published values that would work as configured:
                        """ + "  " + String.join("\n  ", offences))
                .isEmpty();
    }

    /**
     * <strong>The same sentence is written in four places and asserted in one.</strong>
     *
     * <p>{@link #saysWhatRotationActuallyDoes()} pins the Java message. The three prose
     * copies — {@code .env.prod.example} and {@code docs/self-hosting.md}, which says it
     * twice — were pinned by nothing, so any of them could drift back to "rotating signs
     * everyone out" silently, and the one an operator reads is whichever they opened.
     *
     * <p>The claim is asserted the same way in every copy, because it is one claim: rotation
     * rejects access tokens and ends nothing, so revoking refresh sessions is a separate
     * step that has to be named. Told otherwise, an operator who has just learned their
     * signing key is public concludes the cleanup already happened.
     */
    @Test
    void everyCopyOfTheRotationSentenceSaysTheSameThing() {
        for (String path : List.of(".env.prod.example", "docs/self-hosting.md")) {
            Path file = REPO_ROOT.resolve(path);
            String text = PublishedCredentials.read(file);

            for (String purge : List.of("signs out", "signs everyone out", "signed out",
                    "invalidates every existing session", "logs everyone out", "ends every session")) {
                assertThat(text)
                        .withFailMessage("""

                                %s claims that rotating JWT_SECRET ends sessions ("%s"). It does not: \
                                refresh tokens are opaque random values independent of the signing \
                                key, and /api/auth/refresh is public, so every client takes one 401 \
                                and silently re-issues.

                                This exact sentence was wrong in four places at once and is corrected \
                                in all four (HD-200). Saying it again tells an operator who has just \
                                learned their key is public that the cleanup is already done - so \
                                they will not revoke refresh sessions, will not delete unused reset \
                                links, and will not audit administrators.""", path, purge)
                        .doesNotContain(purge);
            }

            assertThat(text)
                    .withFailMessage("""

                            %s explains rotating JWT_SECRET without naming the step that rotation \
                            does NOT perform. Say that sessions survive it, and name revoking them \
                            (refresh_tokens) - a remedy nobody is told about is one nobody \
                            performs.""", path)
                    .contains("refresh_tokens");

            assertThat(text)
                    .withFailMessage("""

                            %s names the refresh-session cleanup but not the reset/setup links \
                            (password_resets). That is the OTHER thing rotation does not reach, and \
                            it is the one an attacker keeps: an admin session forged with the old key \
                            can issue a setup link, and the row it leaves has a seven-day life, is \
                            neither a session nor an access token, and survives both the key rotation \
                            and the refresh_tokens DELETE.

                            This claim is asserted in all THREE copies (this file, the two others in \
                            the list above, and JwtService.ROTATION) because it is one claim - the \
                            Java one said "audit ... recent password resets" for a release while both \
                            prose copies carried the statement, and the copy an operator reads is \
                            whichever they happened to open.""", path)
                    .contains("password_resets");
        }
    }

    /**
     * <strong>The positive control: the scan is asked to report something.</strong>
     *
     * <p>{@code assertThat(scanned).isNotEmpty()} catches exactly one failure — reading no
     * files at all — and nothing else. Drop {@code md} from {@link #SCANNED_EXTENSIONS}, or
     * add {@code docs} to {@link #SKIPPED_DIRECTORIES}, and the scan stays green while blind
     * to the file class where two of this ticket's three defects lived. A scan that cannot
     * fail is indistinguishable from a scan that passes.
     *
     * <p>So every defect shape this repository has actually shipped is replayed through the
     * real predicate over an in-memory fixture, and each must be reported — together with the
     * shapes that must NOT be, because a control that only proved the rule fires would be
     * satisfied by returning {@code true}.
     *
     * <p>The fixtures are assembled at runtime rather than written as literals: this file is
     * inside the scan, and a fixture written out would be one more published credential.
     */
    @Test
    void theScanReportsEveryDefectShapeThisRepositoryHasShipped() {
        record Case(String path, String text, boolean reported, String why) {
        }
        var localDevStack = PublishedCredentials.localDevStackCredentials();
        var cases = List.of(
                new Case(".env.prod.example", assign("DB_PASSWORD", "DB_PASSWORD"), true,
                        "the template line that AGREED with the Postgres container it also seeded"),
                new Case("docs/observability.md", assign("DB_MONITOR_PASSWORD", "a-strong-password"), true,
                        "a pg_monitor login published under a recommended-for-prod heading"),
                new Case("docs/observability.md", createRole("a-strong-password"), true,
                        "the SQL dialect of that one - pasted into a production database as it stands"),
                new Case("docs/self-hosting.md",
                        yaml("JWT_SECRET", "Zq4mR7vL2xN9pK6wT3sY8bF5hJ0dC1gA4eU7iO2n"), true,
                        "a signing key long enough to pass the length check, in a copy-pasteable block"),
                new Case("docs/self-hosting.md", dockerFlag("POSTGRES_PASSWORD", "hamstrack"), true,
                        "the restore drill: a dev-stack password on a container holding a copy of production"),
                new Case("docs/ops-prod-hardening.md", dockerFlag("POSTGRES_PASSWORD", "hamstrack"), true,
                        "the same drill in the other runbook"),
                // In COLUMN 1 of a later line, which is how a shell script writes one and is
                // the shape FLAG_ASSIGNMENT could not see until (?m) was added to it.
                new Case("ops/deploy/apply-config.sh",
                        "set -euo pipefail\n" + "export " + assign("API_TOKEN", "literal-token"), true,
                        "ops/** runs on the production box, where nothing is illustrative"),
                new Case("docker-compose.observability.yml", yaml("GF_SECURITY_ADMIN_PASSWORD", "admin"), true,
                        "the production stack may not borrow the development stack's password"),
                // PowerShell, which is the dialect the Windows half of our own runbooks is
                // written in - and which the line- and flag-anchored patterns are each blind
                // to for a different reason. It stayed invisible only because every case in
                // the tree happens to carry a drill-only- label today.
                new Case("docs/ops-prod-hardening.md", powershell("DB_PASSWORD", "hamstrack"), true,
                        "the restore drill in PowerShell form: the dev stack's password on a container "
                        + "holding a copy of production"),
                // 40 bytes, i.e. long enough to pass the length check - the same trap as the
                // YAML fixture above, so this case fails for the PowerShell dialect and not
                // for a secret that would have been refused anyway.
                new Case("docs/ops-prod-hardening.md",
                        powershell("JWT_SECRET", "Zq4mR7vL2xN9pK6wT3sY8bF5hJ0dC1gA4eU7iO2n"), true,
                        "a signing key set for a command an operator pastes into a terminal"),

                // The other half of the same wrong assumption, found while checking whether
                // the truncation hid anything in the opposite direction: the flag dialect
                // ended its value at a QUOTE as well as at a space, so `export
                // DB_PASSWORD='...'` was read as the empty string and allowed as "not a
                // value at all". A working credential, published, invisible - and one line
                // in docs/launch-day-runbook.md already uses that form (a blank, so nothing
                // leaked, and nothing would have said so if it had not been).
                new Case("ops/deploy/apply-config.sh", quotedFlag("API_TOKEN", "literal-token"), true,
                        "a quoted value in the flag dialect is a value, not an empty one"),

                // A blank that is never closed. One token, yes - but only within its line: if
                // the blank could run past the line end it would swallow the `export` below,
                // and the literal credential on that line would be reported by nothing.
                new Case("ops/deploy/apply-config.sh",
                        "docker run -e " + assign("API_TOKEN", "<unclosed") + "\n"
                        + "export " + assign("API_TOKEN", "literal-token") + "\n", true,
                        "an unfilled blank is one token, but never across a line break"),

                new Case("docker-compose.yml", yaml("POSTGRES_PASSWORD", "hamstrack"), false,
                        "the local dev stack, in the file that creates it"),
                // The multi-word blank, in EVERY dialect that ends a value at a delimiter.
                // The reasoning ("a blank contains spaces, so truncating it turns a
                // placeholder into a word") was written out on the line dialect - the one
                // that cannot truncate - and carried to neither sibling that can, so a
                // runbook writing `-e DB_PASSWORD=<from .env>` was reported for publishing
                // `<from`: the seal refusing the very form remedy 2 tells its reader to use.
                // Both remedy-2 examples in that message, <a strong password> and <any unique
                // string>, have spaces in them, so a dialect proved only with a one-word
                // blank is proved with the one input that cannot fail.
                new Case("docs/self-hosting.md", assign("DB_PASSWORD", "<a strong password>"), false,
                        "an unfilled blank, in the line dialect"),
                new Case("docs/design/flyway-squash-procedure.md",
                        dockerFlag("DB_PASSWORD", "<from .env>"), false,
                        "the same blank in the flag dialect, where the value ends at whitespace"),
                new Case("docs/ops-prod-hardening.md",
                        powershellUnquoted("DB_PASSWORD", "<from .env>"), false,
                        "and unquoted in PowerShell, where it ends at whitespace or a `;`"),
                new Case("docs/self-hosting.md", dockerFlag("POSTGRES_PASSWORD", "drill-only-password"), false,
                        "self-labelled as a throwaway, and the label travels with the value"),
                new Case(".env.prod.example", assign("JWT_SECRET", ""), false,
                        "empty is not a value, and the guard that fires on absence gets to fire"),
                new Case("docs/self-hosting.md", yaml("JWT_SECRET", "${JWT_SECRET:?set it in .env}"), false,
                        "an interpolation, which IS the refusal"),
                new Case("docs/self-hosting.md",
                        assign("JWT_SECRET", "change-me-to-a-random-string-of-32" + "-plus-bytes"), false,
                        "refused by the application, which also reaches the installs that copied it"),
                new Case("CLAUDE.md", powershell("DB_PASSWORD", "hamstrack"), false,
                        "the same PowerShell form in a contributor's doc, naming the dev container"),
                new Case("docs/ops-prod-hardening.md", powershell("DB_PASSWORD", "drill-only-password"), false,
                        "self-labelled, and the label survives being pasted into a terminal"));

        var wrong = new ArrayList<String>();
        for (Case scenario : cases) {
            Path file = Path.of(scenario.path());
            var found = PublishedCredentials.assignments(file, scenario.text());
            if (found.isEmpty()) {
                wrong.add(scenario.path() + " - NOTHING WAS EVEN RECOGNISED AS AN ASSIGNMENT ("
                          + scenario.why() + ")");
                continue;
            }
            boolean reported = found.stream().anyMatch(a -> isOffence(a, localDevStack));
            if (reported != scenario.reported()) {
                wrong.add(scenario.path() + " - expected " + (scenario.reported() ? "REPORTED" : "allowed")
                          + ", got " + (reported ? "REPORTED" : "allowed") + " (" + scenario.why() + ")");
            }
        }

        assertThat(wrong)
                .withFailMessage("""

                        THE SCAN NO LONGER SEES WHAT IT WAS BUILT TO SEE (HD-200).

                        These are the defect shapes this repository has actually shipped, replayed \
                        through the same predicate the repository-wide scan uses, over fixtures held \
                        in memory. A green scan beside a red control means the scan stopped reaching \
                        a file class - the usual causes are dropping an extension from \
                        SCANNED_EXTENSIONS, adding a directory to SKIPPED_DIRECTORIES, or narrowing \
                        what counts as production configuration.

                        The allowed cases matter as much: they are what stops this being widened \
                        into a rule that reports everything and is therefore switched off.

                        Disagreements:
                        """ + "  " + String.join("\n  ", wrong))
                .isEmpty();
    }

    /**
     * The other half of the same control: the classification those fixtures rely on, and the
     * reach of the walk they stand in for.
     */
    @Test
    void theScanReachesTheFileClassesTheControlAssumes() {
        assertThat(SCANNED_EXTENSIONS)
                .withFailMessage("""

                        The scan stopped reading a file class it was built for. Markdown is where \
                        two of this ticket's three defects lived (a quick-start compose and a \
                        restore drill, both inside an operator manual), .yml is every compose file, \
                        and .properties is where a default can be baked into the application \
                        itself. Removing one of these is how this scan goes quiet without going \
                        red.""")
                .contains("md", "yml", "properties");

        assertThat(isScannable(REPO_ROOT.resolve("docs/self-hosting.md")))
                .withFailMessage("""

                        docs/self-hosting.md is no longer reachable by the walk, so every rule below \
                        is being applied to nothing. Adding `docs` to SKIPPED_DIRECTORIES has \
                        exactly this effect and reads like a performance tweak.""")
                .isTrue();

        assertThat(PublishedCredentials.OPERATOR_FACING_DOCUMENTS)
                .allSatisfy(path -> assertThat(REPO_ROOT.resolve(path))
                        .withFailMessage("OPERATOR_FACING_DOCUMENTS names %s, which does not exist - a "
                                + "renamed runbook silently leaves this rule pointing at nothing", path)
                        .isRegularFile());
    }

    /**
     * <strong>The development-stack exemption, pinned to its size for the same reason the two
     * denylists are.</strong>
     *
     * <p>It is the one escape in this rule keyed to a bare VALUE rather than to a
     * (file, variable) pair, and one of its two members — {@code admin} — is an ordinary
     * English word. So a single unused {@code X_PASSWORD: hunter2} added to a development
     * compose file makes {@code hunter2} acceptable everywhere outside production
     * configuration, in files that have nothing to do with that container.
     *
     * <p>The standard for a member is the evidentiary one the denylists carry: a container
     * <em>this repository starts on a developer's machine</em> genuinely needs that value,
     * and the README, {@code docker compose up} and CI all have to agree on it. If you
     * cannot point at the container, the value wants a {@code dev-only-…} label instead —
     * which travels with it into whatever file someone pastes it into, where membership of
     * a set in a test does not.
     */
    @Test
    void theDevelopmentStackExemptionIsPinnedToItsSize() {
        assertThat(PublishedCredentials.localDevStackCredentials())
                .withFailMessage("""

                        The set of values exempted because the local development stack uses them \
                        changed size. It is not a list of acceptable passwords, and it is exempt by \
                        VALUE - so every string in it is acceptable in every non-production file in \
                        this repository, under any variable name at all.

                        Name the container that needs it (docker-compose.yml or a \
                        docker-compose.*.dev.yml at the repository ROOT - a compose file in a \
                        subdirectory deliberately no longer counts), then update this count. If \
                        there is no such container, the value wants a dev-only-... label instead, \
                        which stays true wherever it is pasted.""")
                .hasSize(2);
    }

    /**
     * The classification, asserted as the sentence the failure message has always used: a
     * document an operator pastes into a terminal is production configuration, and a compose
     * file is a development one only at the repository root.
     */
    @Test
    void operatorFacingDocumentsAreProductionConfiguration() {
        for (String path : List.of("docs/self-hosting.md", "docs/observability.md",
                "docs/ops-prod-hardening.md", "ops/backup/hamstrack-backup.sh",
                ".env.prod.example", "docker-compose.prod.yml", "docker-compose.observability.yml")) {
            assertThat(PublishedCredentials.isProductionConfiguration(Path.of(path)))
                    .withFailMessage("`%s` is no longer production configuration, so the local "
                            + "development stack's credentials became acceptable in it - which is how a "
                            + "restore drill came to publish a working Postgres password", path)
                    .isTrue();
            assertThat(PublishedCredentials.isProductionConfiguration(REPO_ROOT.resolve(path)))
                    .withFailMessage("`%s` classifies differently depending on whether it arrived from "
                            + "git ls-files or from a walk of the tree - the two callers of this rule "
                            + "must agree", path)
                    .isTrue();
        }

        assertThat(PublishedCredentials.isDevelopmentCompose(Path.of("docker-compose.yml")))
                .withFailMessage("The repository's own development compose file stopped being one")
                .isTrue();
        assertThat(PublishedCredentials.isDevelopmentCompose(Path.of("examples/docker-compose.yml")))
                .withFailMessage("""

                        A docker-compose.yml in a SUBDIRECTORY classifies as the local development \
                        stack. That is a repository-wide exemption granted by adding a file: every \
                        credential in it joins localDevStackCredentials(), which is exempt by value \
                        everywhere outside production configuration.""")
                .isFalse();
    }

    private static String assign(String variable, String value) {
        return variable + "=" + value;
    }

    private static String yaml(String variable, String value) {
        return "      " + variable + ": " + value;
    }

    private static String dockerFlag(String variable, String value) {
        return "docker run -d --name scratch -e " + variable + "=" + value + " postgres:16-alpine";
    }

    /**
     * The same dialect with the value quoted — how a shell script writes one whose value has
     * a space, and how {@code docs/launch-day-runbook.md} writes its {@code export}.
     */
    private static String quotedFlag(String variable, String value) {
        return "export " + variable + "='" + value + "'";
    }

    /**
     * PowerShell's form, written the way the runbooks actually write it: two assignments on
     * one line, separated by {@code ;}, values double-quoted. Both of those are why the
     * other two patterns could not see it.
     */
    private static String powershell(String variable, String value) {
        return "$env:DB_USERNAME=\"hamstrack\"; $env:" + variable + "=\"" + value + "\"";
    }

    /**
     * The same dialect without the quotes, which PowerShell accepts and which our own
     * runbooks mix with the quoted form. The quotes are what made a multi-word blank survive
     * there; unquoted, the value ends at whitespace and the blank is only one token if the
     * pattern says so.
     */
    private static String powershellUnquoted(String variable, String value) {
        return "$env:DB_USERNAME=hamstrack; $env:" + variable + "=" + value;
    }

    /** Assembled, quote by quote, so no literal {@code PASSWORD '…'} appears in a scanned file. */
    private static String createRole(String value) {
        return "CREATE ROLE hamstrack_exporter LOGIN PASSWORD " + "'" + value + "'" + ";";
    }

    /**
     * <strong>The rule itself, in one place, so the scan and its positive control cannot
     * drift apart.</strong> Would this published assignment work as configured?
     */
    private static boolean isOffence(PublishedCredentials.Assignment found, Set<String> localDevStack) {
        String value = found.value();
        if (PublishedCredentials.isNotAValue(value)
                || PublishedCredentials.isUnfilledPlaceholder(value)
                || PublishedCredentials.isSelfLabelledThrowaway(value)
                || refusedByTheApplication(found.variable(), value)) {
            return false;
        }
        // A container this repository starts on localhost - but never in something an
        // operator copies onto, or runs against, a server.
        return !(localDevStack.contains(value)
                 && !PublishedCredentials.isProductionConfiguration(found.file()));
    }

    /**
     * Would the running application refuse this value for this variable? Two do — the signing
     * key and the seeded administrator's password — and that is the only ground for
     * publishing a credential that also protects the installations already running it.
     */
    private static boolean refusedByTheApplication(String variable, String value) {
        try {
            switch (variable) {
                case "JWT_SECRET" -> validate(value);
                case "SEED_ADMIN_PASSWORD" -> DataSeeder.rejectPublishedPassword(value, "admin@example.com");
                default -> {
                    return false;
                }
            }
            return false;
        } catch (IllegalStateException refused) {
            return true;
        }
    }

    private static String refusalFor(String secret) {
        try {
            validate(secret);
        } catch (IllegalStateException refused) {
            return refused.getMessage();
        }
        throw new AssertionError("`" + secret + "` was accepted, so there is no refusal to read - see "
                + "refusesThePlaceholdersThisProjectHasPublished");
    }

    private static String templateValue() throws IOException {
        assertThat(TEMPLATE)
                .withFailMessage(".env.prod.example was not found at %s — this test reads the repository's "
                        + "own copy, so it must run from the module root", TEMPLATE.toAbsolutePath())
                .isRegularFile();
        // Split on \n only: a trailing \r would be part of the value, and is exactly the
        // shape of "empty" that is not empty (see EnvTemplateGuardTest).
        return Stream.of(Files.readString(TEMPLATE, StandardCharsets.UTF_8).split("\n", -1))
                .filter(line -> line.startsWith(TEMPLATE_KEY))
                .map(line -> line.substring(TEMPLATE_KEY.length()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("`.env.prod.example` no longer declares the signing key "
                        + "at all — it must ship the line empty, not drop it: a variable a reader cannot see "
                        + "is one they cannot set"));
    }

    private static boolean isScannable(Path path) {
        for (Path segment : path) {
            if (SKIPPED_DIRECTORIES.contains(segment.toString())) {
                return false;
            }
        }
        if (!Files.isRegularFile(path)) {
            return false;
        }
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 && SCANNED_EXTENSIONS.contains(name.substring(dot + 1));
    }
}
