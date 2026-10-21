package com.hamstrack.ops;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>HD-199 — step 2b of {@code ops/deploy/apply-config.sh} decides three ways, two
 * flags mean two different things, and the pin is parsed the way Compose parses it.</strong>
 *
 * <p>None of that had a committed guard. The twelve-case check that established the parser's
 * parity with Compose lived in a scratchpad and this repository never saw it, which is the
 * shape of failure the throttle epic already paid for once: a property that holds "by
 * construction" holds exactly until the construction moves, and then holds silently. This
 * test drives the <em>real script</em> — the file a deploy places on the box — against
 * scratch directories, so a change to its decision table has to be a deliberate edit here.
 *
 * <p><strong>What is faked and what is not.</strong> {@code docker} and {@code flock} are
 * replaced by stubs on {@code PATH}: the stub records its arguments (which is how the
 * bind-mount restart is observed) and succeeds. Everything else is the script itself — the
 * manifest parse, the never-sync guards, the {@code .env} parse, the pin decision, the
 * staging and swapping, and the stamping. What this cannot prove is that Compose really
 * gives the process environment precedence over {@code --env-file}, or that it really drops
 * a whitespace-preceded {@code #} tail; those were verified against a real
 * {@code docker compose config} and are recorded in
 * {@code docs/design/config-delivery-proposal.md} §16. What it proves is that the script's
 * reading of a line cannot drift away from the answer that was verified.
 *
 * <p><strong>Where it runs, and why a pass on one platform says nothing about the other.</strong>
 * These tests run on Linux CI and on a developer's Windows box, and they skip only where there
 * is no {@code bash} at all (a Windows machine without Git Bash on {@code PATH}). The two
 * platforms do not agree about the stubs: a shell only executes a file on {@code PATH} that
 * carries an execute bit, and it silently SKIPS one that does not and keeps searching — so a
 * stub written at mode 644 is not a stub, it is a hole in {@code PATH} through which the name
 * resolves to whatever the machine really has. On NTFS there is no execute bit to get wrong and
 * bash runs the file from its shebang, so Windows passed this class while every stub was
 * unexecutable; on the first Linux run the same code found the runner's real {@code docker} and
 * five cases failed with a plausible-looking script error. The stubs are therefore given the
 * execute bit wherever the filesystem has one, and {@link #assertStubsAreExecutable} refuses to
 * launch the script if any file on that {@code PATH} lacks it, naming it as a stub problem.
 * <strong>A green run on a platform that cannot express a permission is not evidence about a
 * platform that can</strong> — it is the absence of the question, and this class shipped that
 * mistake once.
 */
class ApplyConfigPinGuardTest {

    private static final Path SCRIPT = Path.of("ops/deploy/apply-config.sh");

    /**
     * The failure message is the propagation checklist, deliberately rather than a comment:
     * whoever trips this is editing the applier, and what they need is what each branch was
     * for and what else moves with it.
     */
    private static final String CHECKLIST = """

            ops/deploy/apply-config.sh is the file a deploy PLACES on the production box and
            then RUNS, so a change here changes production at the next merge. Step 2b is the
            only thing standing between an incident's deliberately held-back image and the
            newest configuration tree, and every branch of it exists because of an ordering
            somebody walked:

              * PIN UNMOVED -> PROCEED. docs/self-hosting.md tells every self-hoster to pin,
                so "pinned" is a steady state for most of this script's audience. Refusing on
                the pin EXISTING answers that audience "no" for ever, behind a flag they
                retype every time.

              * PIN MOVED -> REFUSE, and PIN WITH NO STAMP -> REFUSE. Nothing pins the
                CONFIGURATION beside the image. Syncing a newer tree onto a rolled-back image
                re-applies, within minutes, whatever a configuration-caused incident was
                rolling back.

              * --allow-pinned PROCEEDS AND DOES NOT STAMP. This is the ordering that made
                the two flags necessary: pin for an incident, CI goes red as designed, six
                hours later an urgent config fix or the DeployImagePinned alert sends the
                operator to a hand run. If that run re-stamps, pin and stamp agree again, and
                the NEXT MERGE's unattended run reads "unmoved" and proceeds -- newest config
                onto the held-back image, no flag, no refusal, and a log line saying
                everything is normal. An override that can be turned into a permanent
                disarming by fatigue is not an override.

              * --adopt-pin PROCEEDS AND STAMPS. The reader who has genuinely moved version
                types one more word, once.

              * THE PIN IS READ THE WAY COMPOSE READS IT. A line Compose honours and this
                parse misses reads as unset, which is `latest`, which is "not pinned" -- a
                fail-OPEN miss in the one check whose whole job is to refuse. And the pin must
                live in .env: Compose gives the PROCESS ENVIRONMENT precedence, the header of
                this script tells operators to run it under `sudo -E`, and an exported pin
                would bypass the guard, deploy a tag the stamp then misreports, and evaporate
                at the end of that shell.

              * A MANIFEST ENTRY IS NORMALISED ONCE. `./observability/` and `observability/`
                name the same path; the moment the second spelling became legal it applied,
                stamped and drift-checked correctly while silently skipping the bind-mount
                restart -- Grafana holding a deleted inode with all three drift scopes reading
                0, which is this ticket's own failure class one layer down.

              * .env AND THE CADDYFILE ARE NEVER SYNCED -- in every spelling, and also when
                carried INSIDE a synced directory, which is a file no manifest line names.
                .env holds this box's secrets and its own decisions and is where the rollback
                lever lives; the production Caddyfile carries a hand-added Cloudflare
                trusted_proxies block this repository's copy does not, so placing ours
                downgrades production silently. Both guards match on the NORMALISED entry, so
                normalisation must stay above them: stripped once, `Caddyfile///` became
                `Caddyfile/`, matched neither arm, and was stopped only by an accident of
                directory resolution -- with a refusal that blamed the release tree.
                "Every spelling" includes a PREFIX, not only a dot: a secret file in this
                repository is named `<something>.env` -- `ops/loadtest/config.env` (HD-186),
                `ops/backup/backup.env` (HD-187) -- and that shape matched none of `.env`,
                `.env.*`, `*/.env`, `*/.env.*` until the `*.env` arm was added. The mirror
                rule is a CLASS and not a file: a `<name>.env.example` TEMPLATE must stay
                syncable, because it is the instructions for the `<name>.env` being refused.
                A new template goes into the syncable loop below -- a one-element List.of
                guarding a two-member category is how that claim went stale the first time.
                And a refusal must describe the entry the operator TYPED: the `.env.*` arms
                refuse names that do not end in `.env`, so a message saying they do is a
                falsehood about the line in front of them.

            What else moves with a change here: docs/release-checklist.md (the rollback
            section), docs/ops-prod-hardening.md section 3, docs/self-hosting.md ("Applying
            repository configuration"), .env.prod.example (APP_IMAGE_TAG), the
            DeployImagePinned annotation in observability/grafana/provisioning/alerting/rules.yml,
            .github/workflows/deploy.yml (which passes NO flag, and must not), and
            docs/design/config-delivery-proposal.md sections 7.2, 11 and 16.
            """;

    private static String bash;

    @TempDir
    Path work;

    @BeforeAll
    static void locateBash() {
        bash = findBash();
    }

    // --- the three states of step 2b ------------------------------------------------

    @Test
    void theThreeStatesOfThePinDecideDifferently() throws Exception {
        var failures = new ArrayList<String>();

        // Unpinned: no APP_IMAGE_TAG at all. `latest` is Compose's default and a default is
        // not a pin, so a box that has never been applied to is not refused for being new.
        var unpinned = deployment("unpinned", "OTHER=1\n", null);
        var r1 = run(unpinned);
        expect(failures, "unpinned box proceeds", r1.exit() == 0, r1);
        expect(failures, "unpinned box stamps latest", "latest".equals(unpinned.stamp()), r1);

        // Pinned and unmoved: the steady state docs/self-hosting.md prescribes.
        var unmoved = deployment("unmoved", "APP_IMAGE_TAG=0.17.0\n", "0.17.0");
        var r2 = run(unmoved);
        expect(failures, "pinned-and-unmoved proceeds", r2.exit() == 0, r2);
        expect(failures, "pinned-and-unmoved says it is a steady-state re-apply",
                r2.output().contains("steady-state re-apply"), r2);
        expect(failures, "pinned-and-unmoved keeps the tag stamped", "0.17.0".equals(unmoved.stamp()), r2);

        // Moved: the rollback this check exists for.
        var moved = deployment("moved", "APP_IMAGE_TAG=0.17.0\n", "latest");
        var r3 = run(moved);
        expect(failures, "moved pin refuses", r3.exit() != 0, r3);
        expect(failures, "moved pin replaced nothing",
                !Files.exists(moved.box().resolve("docker-compose.prod.yml")), r3);
        expect(failures, "moved pin left the stamp alone", "latest".equals(moved.stamp()), r3);
        // The refusal must name an action for BOTH readers, and the right one for each.
        expect(failures, "moved refusal tells the version-bumper to --adopt-pin",
                r3.output().contains("--adopt-pin"), r3);
        expect(failures, "moved refusal tells the roll-back operator to leave it refused and un-pin",
                r3.output().contains("un-pin"), r3);

        // Unstamped: the state of EVERY box the first time this ships.
        var unstamped = deployment("unstamped", "APP_IMAGE_TAG=0.17.0\n", null);
        var r4 = run(unstamped);
        expect(failures, "a pinned box with no stamp at all refuses", r4.exit() != 0, r4);
        expect(failures, "unstamped refusal wrote no stamp", unstamped.stamp() == null, r4);
        // The paragraph the `moved` branch spends on the rollback reader must be here too:
        // this is the state of every box on the first run, and if production happens to be
        // rolled back that day, adopting makes the rolled-back tag the intended one for ever.
        expect(failures, "unstamped refusal warns the rolled-back reader NOT to adopt",
                r4.output().contains("do NOT adopt"), r4);
        expect(failures, "unstamped refusal tells that reader to un-pin when the incident ends",
                r4.output().contains("un-pin"), r4);
        expect(failures, "unstamped refusal still tells the by-policy pinner to --adopt-pin",
                r4.output().contains("--adopt-pin"), r4);

        assertThat(failures).withFailMessage(CHECKLIST + "\nFailed: " + failures).isEmpty();
    }

    // --- the two overrides, and the ordering that made them two ----------------------

    /**
     * The regression this whole round exists for, walked as the four steps that produce it:
     * an override that re-stamps disarms the guard for the next unattended run, not for one
     * run. {@code --allow-pinned} must leave the disagreement in place; only
     * {@code --adopt-pin} may resolve it.
     */
    @Test
    void allowPinnedOverridesOneRunAndAdoptPinOverridesThePin() throws Exception {
        var failures = new ArrayList<String>();

        // 1-2. Production is pinned to 0.17.0 for an incident; the last apply here was the
        //      CI deploy that placed configuration beside `latest`. Unattended CI goes red.
        var d = deployment("ordering", "APP_IMAGE_TAG=0.17.0\n", "latest");
        var refused = run(d);
        expect(failures, "step 2: the unattended run refuses", refused.exit() != 0, refused);

        // 3. Six hours in: an urgent config fix, by hand, with the override.
        var overridden = run(d, Map.of(), "--allow-pinned");
        expect(failures, "step 3: --allow-pinned proceeds", overridden.exit() == 0, overridden);
        expect(failures, "step 3: --allow-pinned applied the configuration",
                Files.exists(d.box().resolve("docker-compose.prod.yml")), overridden);
        expect(failures, "step 3: --allow-pinned did NOT re-stamp the image tag",
                "latest".equals(d.stamp()), overridden);
        expect(failures, "step 3: --allow-pinned says the stamp was left alone",
                overridden.output().contains("leaving") && overridden.output().contains(".deployed-image-tag"),
                overridden);
        // The sha and the checksums DO move: they describe what is now on disk, which this
        // run really did change. Only the image tag is withheld.
        expect(failures, "step 3: --allow-pinned still stamped the sha",
                "testsha".equals(read(d.box().resolve(".deployed-sha")).strip()), overridden);

        // 4. THE POINT. The next merge's unattended run must refuse again.
        var refusedAgain = run(d);
        expect(failures, "step 4: the next unattended run refuses AGAIN", refusedAgain.exit() != 0, refusedAgain);
        expect(failures, "step 4: it still calls the pin MOVED rather than a steady state",
                !refusedAgain.output().contains("steady-state re-apply"), refusedAgain);

        // Adoption is the separate, deliberate act.
        var adopted = run(d, Map.of(), "--adopt-pin");
        expect(failures, "--adopt-pin proceeds", adopted.exit() == 0, adopted);
        expect(failures, "--adopt-pin re-stamps the tag", "0.17.0".equals(d.stamp()), adopted);

        var afterAdoption = run(d);
        expect(failures, "after adoption an unflagged run proceeds", afterAdoption.exit() == 0, afterAdoption);
        expect(failures, "after adoption it is a steady-state re-apply",
                afterAdoption.output().contains("steady-state re-apply"), afterAdoption);

        // The same split on a box with no stamp at all: overriding must not invent one.
        var fresh = deployment("ordering-fresh", "APP_IMAGE_TAG=0.17.0\n", null);
        var freshAllowed = run(fresh, Map.of(), "--allow-pinned");
        expect(failures, "--allow-pinned on an unstamped box proceeds", freshAllowed.exit() == 0, freshAllowed);
        expect(failures, "--allow-pinned on an unstamped box writes NO stamp", fresh.stamp() == null, freshAllowed);
        var freshRefused = run(fresh);
        expect(failures, "…so the next unattended run on it refuses", freshRefused.exit() != 0, freshRefused);
        var freshAdopted = run(fresh, Map.of(), "--adopt-pin");
        expect(failures, "--adopt-pin on an unstamped box stamps", "0.17.0".equals(fresh.stamp()), freshAdopted);

        // A dry run must say what the flags it was GIVEN will do, and never write anything.
        var dry = deployment("ordering-dry", "APP_IMAGE_TAG=0.17.0\n", "latest");
        var dryPlain = run(dry, Map.of(), "--dry-run");
        expect(failures, "--dry-run does not refuse", dryPlain.exit() == 0, dryPlain);
        expect(failures, "--dry-run names both overrides",
                dryPlain.output().contains("--allow-pinned") && dryPlain.output().contains("--adopt-pin"),
                dryPlain);
        var dryAllow = run(dry, Map.of(), "--dry-run", "--allow-pinned");
        expect(failures, "--dry-run --allow-pinned says the real run would NOT re-stamp",
                dryAllow.output().contains("THIS RUN ONLY"), dryAllow);
        var dryAdopt = run(dry, Map.of(), "--dry-run", "--adopt-pin");
        expect(failures, "--dry-run --adopt-pin says the real run WOULD re-stamp",
                dryAdopt.output().contains("re-stamps"), dryAdopt);
        expect(failures, "no dry run wrote a stamp", "latest".equals(dry.stamp()), dryAdopt);

        assertThat(failures).withFailMessage(CHECKLIST + "\nFailed: " + failures).isEmpty();
    }

    // --- the parse, against the cases verified with a real `docker compose config` ----

    /**
     * Each row is "this .env line means this tag". The assertion is uniform because the
     * script's own behaviour distinguishes the two outcomes: the box is stamped with a
     * sentinel no row can produce, so a row that means a PIN is a moved pin and is refused
     * with the tag named back, and a row that means "not pinned" resolves to {@code latest}
     * and proceeds.
     */
    @Test
    void theEnvPinIsParsedTheWayComposeParsesIt() throws Exception {
        var failures = new ArrayList<String>();

        record Row(String what, String envLine, String expected) {}
        List<Row> rows = List.of(
                // Compose honours the `export ` prefix. A parse that misses it reads the line
                // as unset, i.e. `latest`, i.e. "not pinned" — fail-open in a refusing check.
                new Row("export prefix", "export APP_IMAGE_TAG=v0.16.3\n", "v0.16.3"),
                new Row("leading whitespace and export", "   export   APP_IMAGE_TAG=  0.9  \n", "0.9"),
                new Row("double-quoted value", "APP_IMAGE_TAG=\"1.2.3\"\n", "1.2.3"),
                new Row("single-quoted value", "APP_IMAGE_TAG='1.2.3'\n", "1.2.3"),
                new Row("quoted value with a trailing comment", "APP_IMAGE_TAG=\"1.2.3\" # held\n", "1.2.3"),
                // A `#` PRECEDED BY WHITESPACE starts a comment; one INSIDE the value does
                // not. Getting this backwards either loses a pin or invents a different one.
                new Row("whitespace-preceded comment", "APP_IMAGE_TAG=v1 # incident\n", "v1"),
                new Row("hash inside the value", "APP_IMAGE_TAG=v1#incident\n", "v1#incident"),
                // Absent and empty are both Compose's `latest` default, and a default is not
                // a pin: a box with an empty line must not be refused as pinned.
                new Row("absent", "OTHER=1\n", "latest"),
                new Row("empty value", "APP_IMAGE_TAG=\n", "latest"),
                // Compose resolves a duplicated key to the LAST one. Reading the first would
                // report a pin the box is not running.
                new Row("duplicate keys, last wins", "APP_IMAGE_TAG=first\nAPP_IMAGE_TAG=second\n", "second"),
                // Near misses. Each of these would be a pin invented out of a line that is
                // not one — a deploy refused for a reason that does not exist.
                new Row("near-miss: another variable ending in the name", "MYAPP_IMAGE_TAG=nope\n", "latest"),
                new Row("near-miss: a longer key", "APP_IMAGE_TAGS=nope\n", "latest"),
                new Row("near-miss: commented out", "#APP_IMAGE_TAG=nope\n", "latest"));

        int i = 0;
        for (Row row : rows) {
            var d = deployment("parse-" + (i++), row.envLine(), "sentinel-tag");
            var r = run(d);
            if ("latest".equals(row.expected())) {
                expect(failures, "parse [" + row.what() + "] is NOT a pin, so the run proceeds",
                        r.exit() == 0, r);
                expect(failures, "parse [" + row.what() + "] stamps latest",
                        "latest".equals(d.stamp()), r);
            } else {
                expect(failures, "parse [" + row.what() + "] is a moved pin, so the run is refused",
                        r.exit() != 0, r);
                expect(failures, "parse [" + row.what() + "] reads the tag as " + row.expected(),
                        r.output().contains("APP_IMAGE_TAG=" + row.expected()), r);
            }
        }

        assertThat(failures).withFailMessage(CHECKLIST + "\nFailed: " + failures).isEmpty();
    }

    /**
     * Compose gives the process environment precedence over {@code --env-file}, and this
     * script's header tells operators to run it under {@code sudo -E}. An exported pin that
     * the guard could not see would be deployed by step 7, misreported by the stamp, and
     * gone at the end of that shell.
     */
    @Test
    void anExportedPinIsRefusedBecauseOnlyDotEnvSurvivesADeploy() throws Exception {
        var failures = new ArrayList<String>();

        var d = deployment("exported", "APP_IMAGE_TAG=0.4\n", "0.4");
        var hidden = run(d, Map.of("APP_IMAGE_TAG", "9.9.9"));
        expect(failures, "an environment pin that disagrees with .env is refused", hidden.exit() != 0, hidden);
        expect(failures, "…and says the pin must live in .env",
                hidden.output().contains("must live in") && hidden.output().contains(".env"), hidden);
        expect(failures, "…having replaced nothing",
                !Files.exists(d.box().resolve("docker-compose.prod.yml")), hidden);

        // An exported EMPTY value is Compose's `latest`, so it would silently UN-pin a box
        // whose .env is pinned. Same refusal, for the same reason.
        var emptied = run(d, Map.of("APP_IMAGE_TAG", ""));
        expect(failures, "an exported empty value (Compose reads it as latest) is refused",
                emptied.exit() != 0, emptied);

        // Agreeing with the file decides nothing, so it is not worth a refusal.
        var agreeing = run(d, Map.of("APP_IMAGE_TAG", "0.4"));
        expect(failures, "an environment pin that agrees with .env is not in the way",
                agreeing.exit() == 0, agreeing);

        assertThat(failures).withFailMessage(CHECKLIST + "\nFailed: " + failures).isEmpty();
    }

    // --- the manifest entry, normalised once ----------------------------------------

    /**
     * {@code ./observability/} is a legal spelling of {@code observability/}, and before the
     * entry was normalised it applied, stamped and drift-checked correctly while matching
     * neither arm of {@code case "$entry" in observability|observability/*)} — so the
     * bind-mounted services were never restarted and every check still read 0.
     */
    @Test
    void aLeadingDotSlashInTheManifestStillRestartsTheBindMountedServices() throws Exception {
        var failures = new ArrayList<String>();

        // The third spelling is the same path written as the manifest's FINAL LINE WITH NO
        // TRAILING NEWLINE. `read` returns non-zero at EOF having already filled the
        // variable, so a plain `while read` drops that entry — the path is silently never
        // applied while the deploy logs success, which is the failure class this ticket is.
        int n = 0;
        for (String spelling : List.of("observability/\n", "./observability/\n", "observability/")) {
            var label = spelling.strip() + (spelling.endsWith("\n") ? "" : " (no trailing newline)");
            var d = deployment("spelling-" + (n++),
                    "OTHER=1\n", null, "docker-compose.prod.yml\n" + spelling);
            var r = run(d);
            expect(failures, "[" + label + "] applies", r.exit() == 0, r);
            expect(failures, "[" + label + "] places the bind-mounted tree",
                    Files.exists(d.box().resolve("observability/grafana/grafana.ini")), r);
            // WHAT THIS ASSERTS, AND WHAT IT DELIBERATELY NO LONGER CLAIMS TO (HD-333).
            // It asserts that the repair step is REACHED, which is now true of any deploy that
            // replaced anything: step 7b is armed by CHANGED — the same predicate the apply loop
            // walks — and no longer by `case "$entry" in observability|observability/*)`, which was
            // the deleted BIND_MOUNT_SERVICES array in a second spelling.
            //
            // So this line is NOT the spelling seal any more, and saying so is the point: with the
            // path gate gone, a mis-normalised `./observability/` would still reach this log line,
            // and only the COMPARISON inside the step would miss. That comparison needs a container
            // with a mount, which this fixture's deliberately minimal docker stub does not model —
            // it answers `ps -q` and nothing else, and giving it a service list would switch on
            // every verify check and need a whole box modelled to keep this test about spelling.
            // The spelling seal is therefore where the fixture does model containers:
            // ApplyConfigVerifyPhaseTest#onlyAServiceThatBindMountsAReplacedPathIsRestarted spells
            // its manifest entry `./observability/` and asserts the container that mounts inside it
            // is restarted. Here, the spelling is held by the two assertions around this one: the
            // tree lands on the box, and the stamp names it without a `./` prefix.
            expect(failures, "[" + label + "] reaches the bind-mount repair",
                    r.output().contains("a synced configuration path changed"), r);
            // The stamp paths are uniform too, which is what keeps hamstrack-config-drift.sh
            // able to match `find <entry>` output against the lines this script wrote.
            expect(failures, "[" + label + "] stamps the path without a ./ prefix",
                    read(d.box().resolve(".deployed-manifest.sha256")).contains("observability/grafana/grafana.ini")
                            && !read(d.box().resolve(".deployed-manifest.sha256")).contains("./observability"), r);
        }

        // The target directory itself is still refused, in every spelling of it: `cp -a`
        // would stage the whole release tree and the swap would fail mid-loop, after earlier
        // entries had been applied.
        for (String spelling : List.of(".", "./", ".//")) {
            var d = deployment("self-" + spelling.length(), "OTHER=1\n", null,
                    "docker-compose.prod.yml\n" + spelling + "\n");
            var r = run(d);
            expect(failures, "[" + spelling + "] naming the target itself is refused", r.exit() != 0, r);
            expect(failures, "[" + spelling + "] says so rather than failing mid-apply",
                    r.output().contains("names the target directory itself"), r);
        }

        assertThat(failures).withFailMessage(CHECKLIST + "\nFailed: " + failures).isEmpty();
    }

    /**
     * The two never-sync guards, which had no test at all — and they belong beside the
     * normalisation cases because normalisation is what decides whether they match. Stripping
     * ONE trailing slash left {@code Caddyfile///} as {@code Caddyfile/}, which matches neither
     * arm of the never-sync case — neither the bare name nor the "any directory, then the name"
     * glob beside it — so it PASSED the guard, and was stopped one line later only because a
     * trailing slash forces directory resolution and Caddyfile is a regular file. Closed by
     * accident, and blaming the wrong thing ("does not exist in the release tree"): a reader
     * told that adds the file to the release tree rather than removing the entry from the
     * manifest.
     *
     * <p>The second half is the guard the manifest cannot express: a DIRECTORY entry places
     * files the manifest never names, so a {@code .env} or a {@code Caddyfile} committed inside
     * {@code observability/} would reach the box through a line that reads {@code observability/}.
     * A dry run caught exactly that overwriting the hardened Caddyfile with the bare one.
     *
     * <p><strong>And the shape the brace could not see was the one this repository actually
     * writes.</strong> Both arms matched a basename that BEGINS with {@code .env}, while a
     * secret file here is named {@code <something>.env}: {@code ops/loadtest/config.env}
     * (HD-186) and {@code ops/backup/backup.env} (HD-187), both inside {@code ops/}, which is
     * synced wholesale, and both named in prose by their own templates as the thing that must
     * not be placed there. The comment above the refusal says the guarantee holds
     * "UNCONDITIONALLY"; a {@code *.env} arm is what makes that sentence true rather than
     * aspirational. The cases below are therefore in three groups, and the third carries the
     * weight — as a CATEGORY, not a file: <strong>a {@code <name>.env.example} template
     * travels; the {@code <name>.env} it describes does not.</strong> Every such template must
     * keep travelling, or the widening deletes the instructions for the file it protects, so
     * a new one belongs in that loop.
     *
     * <p>The first group also checks that the refusal describes the entry that was TYPED.
     * The two {@code .env.} arms — the bare one and the "any directory, then the name" glob
     * beside it — refuse names ending in {@code .local} or {@code .production}, and the
     * message said "ends in .env" for all five arms — an
     * operator who wrote {@code ops/.env.local} was told something demonstrably untrue about
     * their own line, and no case here reached that arm to notice.
     */
    @Test
    void neitherDotEnvNorTheCaddyfileCanBeSyncedInAnySpelling() throws Exception {
        var failures = new ArrayList<String>();

        // Named directly, including the spellings normalisation has to fold away first.
        // `config.env` is the one that was NOT covered: the arms matched `.env` and
        // `.env.*`, i.e. a basename that BEGINS with `.env`, while HD-186's real secret file
        // is `ops/loadtest/config.env` — basename `config.env`, inside a directory this
        // repository syncs wholesale. The comment above the guard says the refusal holds
        // UNCONDITIONALLY, and that sentence is made true here rather than narrowed there.
        int n = 0;
        for (String entry : List.of("Caddyfile", "./Caddyfile", "Caddyfile///", ".env", "./config/.env",
                ".env.local", "./ops/.env.production",
                "config.env", "./ops/loadtest/config.env", "./ops/backup/backup.env")) {
            var d = deployment("never-" + (n++), "OTHER=1\n", null,
                    "docker-compose.prod.yml\n" + entry + "\n");
            var r = run(d);
            expect(failures, "[" + entry + "] is refused", r.exit() != 0, r);
            expect(failures, "[" + entry + "] is refused AS A NEVER-SYNC ENTRY, not for some "
                    + "other reason it happens to trip", r.output().contains("NEVER synced"), r);
            // The accidental pass: `Caddyfile/` reached the existence test and died there.
            expect(failures, "[" + entry + "] does not blame the release tree for a path that is "
                    + "refused on principle",
                    !r.output().contains("does not exist in the release tree"), r);
            // The message has to be true of the line the operator wrote. `.env.local` is
            // refused by the `.env.*` arm and ends in `.local`, so a refusal claiming it
            // "ends in .env" describes a different file than the one on screen.
            expect(failures, "[" + entry + "] is not told a falsehood about its own entry: only "
                    + "a name that ENDS IN .env may be refused with a message saying it does",
                    !r.output().contains("ends in .env") || entry.endsWith(".env"), r);
            expect(failures, "[" + entry + "] replaced nothing",
                    !Files.exists(d.box().resolve("docker-compose.prod.yml")), r);
        }

        // Carried INSIDE a synced directory, which no manifest line names. The scan is
        // per placed file, so the entry that reaches the box is `observability/`.
        for (String planted : List.of(".env", ".env.production", "Caddyfile", "config.env")) {
            var d = deployment("planted-" + planted, "OTHER=1\n", null);
            write(d.src().resolve("observability").resolve(planted), "SECRET=1\n");
            var r = run(d);
            expect(failures, "a " + planted + " inside a synced directory is refused", r.exit() != 0, r);
            expect(failures, "…and the refusal names the file it would have placed",
                    r.output().contains("would place") && r.output().contains(planted), r);
            expect(failures, "…having replaced nothing",
                    !Files.exists(d.box().resolve("docker-compose.prod.yml")), r);
        }

        // The other end of the same widening, and the reason the arm is `*.env` and not
        // `*env*`: the TEMPLATE has to keep travelling. A `<name>.env.example` is
        // documentation an operator reads on the box, it carries no value, and a guard that
        // swallowed it would delete the instructions for the file it is protecting. This is a
        // CATEGORY — the repository has two members today (ops/loadtest/config.env.example
        // for HD-186, ops/backup/backup.env.example for HD-187, the DC-facing one an operator
        // installs to /etc/hamstrack/backup.env) and every prose statement that named only
        // the first went stale the moment the second shipped. A new template is added here.
        // (`.env.example` is a different question and is deliberately still refused by the
        // `.env.*` arm — that name would break every deploy of the product, which is why
        // neither of these templates is called that.)
        for (String syncable : List.of("config.env.example", "backup.env.example")) {
            var d = deployment("syncable-" + syncable, "OTHER=1\n", null);
            write(d.src().resolve("observability").resolve(syncable), "LOAD_PASSWORD_HASH=\n");
            var r = run(d);
            expect(failures, "a " + syncable + " inside a synced directory still applies", r.exit() == 0, r);
            expect(failures, "…and reaches the box",
                    Files.exists(d.box().resolve("observability").resolve(syncable)), r);
        }

        assertThat(failures).withFailMessage(CHECKLIST + "\nFailed: " + failures).isEmpty();
    }

    /**
     * The invariant every comment in {@code .github/workflows/deploy.yml} asserts and nothing
     * checked: <strong>the unattended pipeline passes neither override</strong>. An override in
     * CI is not an override — it is step 2b deleted, on every merge, for everyone, and the
     * failure it stops (newest configuration tree onto an image an incident deliberately held
     * back) is silent by construction. The flags appear in that file's prose on purpose, so this
     * reads the SSM command string alone.
     */
    @Test
    void theUnattendedPipelinePassesNeitherPinOverride() throws IOException {
        var workflow = Path.of(".github/workflows/deploy.yml");
        assertThat(workflow)
                .withFailMessage("%s was not found — this test reads the repository's own copy, so it "
                        + "must run from the module root", workflow.toAbsolutePath())
                .isRegularFile();
        var yaml = Files.readString(workflow, StandardCharsets.UTF_8);

        int start = yaml.indexOf("--parameters 'commands=[");
        int end = start < 0 ? -1 : yaml.indexOf("]'", start);
        assertThat(start >= 0 && end > start)
                .withFailMessage(CHECKLIST + """

                        The SSM command string could not be found in .github/workflows/deploy.yml \
                        (looked for --parameters 'commands=[ … ]'). This test exists to prove that \
                        string passes NEITHER --allow-pinned NOR --adopt-pin to apply-config.sh; if \
                        the deploy step has been rewritten, re-point the extraction rather than \
                        deleting the seal, because an override reaching CI disarms step 2b on every \
                        merge and nothing else would say so.
                        """)
                .isTrue();

        var command = yaml.substring(start, end);
        assertThat(command)
                .withFailMessage(CHECKLIST + """

                        The extracted SSM command string does not invoke apply-config.sh, so this \
                        seal would pass for any content at all. Extracted:

                        %s
                        """, command)
                .contains("apply-config.sh");

        var passed = new ArrayList<String>();
        for (String flag : List.of("--allow-pinned", "--adopt-pin")) {
            if (command.contains(flag)) {
                passed.add(flag);
            }
        }
        assertThat(passed)
                .withFailMessage(CHECKLIST + """

                        .github/workflows/deploy.yml passes %s to apply-config.sh. Both overrides are \
                        for a HUMAN at a hand run: --allow-pinned proceeds once, --adopt-pin declares a \
                        tag intended. An unattended run is exactly the reader step 2b exists to stop, so \
                        a flag here does not override one deploy -- it deletes the guard from every \
                        merge, and the failure it was stopping (the newest configuration tree placed on \
                        an image an incident deliberately held back, re-applying whatever that incident \
                        was rolling back) arrives with no flag, no refusal and a log line saying \
                        everything is normal.
                        """, passed)
                .isEmpty();
    }

    /**
     * "The two scripts carry the same function, deliberately" is a claim two files make about
     * each other, which is the shape that rots quietly: a parse fixed in one and not the other
     * means the guard refuses a pin the drift metric says is not there, or the reverse. The
     * only permitted difference is the name of the variable holding the path to {@code .env}.
     *
     * <p>Reads the files rather than running them, so it holds on a machine with no bash.
     */
    @Test
    void bothScriptsParseTheEnvPinWithTheSameFunction() throws IOException {
        var applier = readImageTagFunction(SCRIPT);
        var drift = readImageTagFunction(Path.of("ops/drift/hamstrack-config-drift.sh"));

        assertThat(applier)
                .withFailMessage(CHECKLIST + """

                        ops/deploy/apply-config.sh and ops/drift/hamstrack-config-drift.sh no longer
                        read APP_IMAGE_TAG the same way. One REFUSES a deploy on this answer and the
                        other PUBLISHES it as hamstrack_deploy_image_pinned, so a divergence means a
                        box whose deploys are blocked while the un-pin reminder says it is not pinned,
                        or a box that publishes a pin nothing enforces. Change both, or neither.

                        applier: %s

                        drift:   %s
                        """, applier, drift)
                .isEqualTo(drift);
    }

    /**
     * The body of {@code read_image_tag}, with the two scripts' different path variable elided.
     * The extraction itself is {@link ScriptHarness#functionBody}, shared with the two other
     * function pairs the applier and the drift script carry (HD-299).
     */
    private static String readImageTagFunction(Path script) throws IOException {
        // The elision moved into ScriptHarness in HD-299's last round, because a SECOND
        // comparison of the same pair (the derived "every function both scripts carry" test)
        // needed the same one permitted difference and would otherwise have been red for a
        // divergence this test had already decided was fine.
        return ScriptHarness.comparableFunctionBody(script, "read_image_tag");
    }

    // --- the pin examples ops PROSE carries, and the fixtures that reproduce them ------

    /** What a concrete {@code <pin variable>=…} example in an ops script's prose is for. */
    private enum PinExample {
        /**
         * A live line under {@code src/test/java} names the pin variable AND this exact value on
         * one physical line. <strong>That co-occurrence is the whole of what is enforced</strong>,
         * and it is weaker than "a fixture feeds this string to the parser": it cannot tell an
         * input from an argument, nor either from prose inside a text block.
         *
         * <p>Three of the four declared members are the strong reading — {@code v0.16.3} and
         * {@code 9.9.9} are {@code .env}-line shapes a row really does feed to the parse, and
         * {@code 0.17.0} is the pin the four-step ordering fixture writes. <strong>{@code latest}
         * is the member where the two readings diverge</strong>, and it is named here rather than
         * left for a reader to discover: {@code apply-config.sh:631} QUOTES a historical bad
         * refusal message rather than illustrating an {@code .env} line, and its witness at
         * {@code :177} is the process-environment override argument, not a parsed string. Nothing
         * about that is wrong for the build — no sweeper is coming for the word {@code latest} —
         * but reword that historical quote and the red you get will be about a coupling that was
         * never a parser example. Move the declaration with it, or drop both.
         */
        REPRODUCED,
        /**
         * A release line a reader copies into their own {@code .env}. Nothing feeds it anywhere, so
         * a sweep onto the current line is correct and is what keeps the instruction true.
         */
        SWEPT,
        /** A metavariable ({@code <the last good tag>}, {@code sha-<7>}) — no value to agree about. */
        PLACEHOLDER
    }

    /**
     * The couplings, and only the couplings: {@code <script>|<value>} for every pin example in ops
     * prose that a fixture reproduces. Declared rather than derived, because the derived form of
     * this question answers itself — a population found BY the co-occurrence cannot then be asked
     * whether the two sides co-occur.
     *
     * <p>Deliberately NOT a declaration of the whole partition. {@code APP_IMAGE_TAG=0.18} in
     * {@code apply-config.sh} is a release line a reader copies, nothing reproduces it, and
     * sweeping it to the next line is what keeps the instruction true: recording it here by value
     * would red the build on that correct sweep, and a seal that reds on the right answer gets
     * deleted. So the rule is one-directional and exact in both directions anyway — anything a
     * fixture reproduces must be named here, and anything named here must still be reproduced.
     *
     * <p><strong>That {@code APP_IMAGE_TAG=0.18} two lines above is a live negative control, and
     * it is load-bearing where it sits — do not "tidy" it into prose.</strong> This javadoc is
     * under {@code src/test/java}, so the value is a candidate witness for itself; it classifies
     * {@code SWEPT} on every green run only because {@link #liveTestLines} drops javadoc. The day
     * that filter stops dropping javadoc, this line becomes a witness, {@code 0.18} flips to
     * {@code REPRODUCED} and the seal reds as an undeclared coupling. The comment filter therefore
     * polices itself, permanently and at zero cost, as long as this example keeps being written
     * out in full rather than described.
     *
     * <p>No entry carries a written reason. Each one is re-derived on every run; a disagreement in
     * either direction is the finding, and the live count is computed by the failure message.
     *
     * <p>The script is part of the key because the witness is found by value alone, so dropping it
     * would let one fixture line answer for two different scripts' comments (the granularity
     * control at the end of the seal fires if that ever becomes possible).
     */
    private static final Set<String> DECLARED_COUPLED_PIN_EXAMPLES = Set.of(
            "ops/deploy/apply-config.sh|0.17.0",
            "ops/deploy/apply-config.sh|9.9.9",
            "ops/deploy/apply-config.sh|latest",
            "ops/drift/hamstrack-config-drift.sh|v0.16.3");

    /**
     * <strong>HD-335 — a comment and the test fixture that justifies leaving it alone agreed only
     * by editorial care.</strong>
     *
     * <p>{@code ops/drift/hamstrack-config-drift.sh:255} spells {@code export APP_IMAGE_TAG=v0.16.3}
     * in prose, and {@link #theEnvPinIsParsedTheWayComposeParsesIt} feeds that exact string to the
     * parser as its {@code export prefix} row. Two reviewers correctly declined to sweep the comment
     * onto the current release line, and both gave a reason that was not load-bearing: the line sits
     * between {@code write_metrics()} (which closes at 241) and {@code read_image_tag()} (which opens
     * at 272), so {@link ScriptHarness#comparableFunctionBody} — the only text comparison between the
     * two scripts — cannot reach it, and changing the value to a current release string was measured
     * to red nothing at all.
     *
     * <p><strong>Why the population is derived from the SCRIPTS and the partition is declared.</strong>
     * A population derived from the co-occurrence itself cannot be asked whether the two sides
     * co-occur, and a population derived from "a version-shaped literal in both an ops script and an
     * ops test" was measured to be unusable: 62 such {@code (file, value)} pairs exist, most of them
     * documentation section numbers ({@code §5.5.3}, {@code §4.7}) that a version regex cannot tell
     * from a version, and the containment test is contaminated by substrings — {@code 6.3} in the
     * drift script resolves to {@link #theEnvPinIsParsedTheWayComposeParsesIt}'s row by matching the
     * INSIDE of {@code v0.16.3}. The anchor is therefore the PARSER rather than the shape of the
     * value — the variable name is read out of {@code read_image_tag}'s own body, and a candidate is
     * a value in a comment line that (a) is assigned to that variable anywhere under {@code ops/},
     * or (b) is version-shaped in a script that defines that parser. Arm (b) exists because the
     * coupling is not always written as an assignment: {@code apply-config.sh:580} narrates
     * "production is pinned to 0.17.0 for an incident" and
     * {@link #allowPinnedOverridesOneRunAndAdoptPinOverridesThePin} walks those four steps with
     * {@code 0.17.0} as its fixture, which arm (a) cannot see. Status is then re-derived for every
     * candidate, and only the couplings are declared.
     *
     * <p>That keeps the honest third answer to "illustration or provenance?". A measurement
     * provenance ({@code measured on Compose v5.1.0}, {@code Engine 25.0.16}) is prose on BOTH
     * sides and is nobody's fixture: it stays a candidate, derives as nothing-reproduces-it, needs
     * no declaration, and is free to be re-measured — demanding that two independent records of one
     * measurement agree would be meaningless. The discriminator is proven to answer both ways on
     * the live population by the floors below: members must exist on BOTH sides of it.
     */
    @Test
    void everyPinExampleInOpsProseDeclaresWhetherAFixtureReproducesIt() throws IOException {
        var scripts = filesUnder(Path.of("ops"), ".sh");
        var tests = filesUnder(Path.of("src/test/java"), ".java");
        var liveLines = liveTestLines(tests);
        var variables = pinVariables(scripts);

        var problems = new ArrayList<String>();
        var derived = new TreeMap<String, PinExample>();
        var witnesses = new TreeMap<String, String>();
        int placeholders = 0;
        var reproducingScripts = new HashSet<String>();

        // A version-shaped token. The lookarounds also delete every dotted quad for free: no arm of
        // `\d+\.\d+(\.\d+)?` survives inside 127.0.0.1 or 169.254.169.254, because each candidate
        // run is adjacent to another `.`-digit — so an IP literal is not a version here by
        // construction rather than by an exclusion somebody has to maintain.
        var versionShaped = Pattern.compile("(?<![\\w.])v?\\d+\\.\\d+(?:\\.\\d+)?(?![\\w.])");
        int versionCandidates = 0;

        for (Path script : scripts) {
            var rel = posixRelative(script);
            var carriesTheParser = Files.readString(script, StandardCharsets.UTF_8).contains("read_image_tag() {");
            var lines = Files.readAllLines(script, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                var line = lines.get(i);
                if (!line.stripLeading().startsWith("#")) {
                    continue;   // a value the script USES is not prose about the value
                }
                var candidates = new ArrayList<String>();
                // (a) assigned to the parsed variable, in any ops script.
                for (String variable : variables) {
                    var m = Pattern.compile("(?<![A-Z0-9_])" + Pattern.quote(variable) + "=(\\S+)").matcher(line);
                    while (m.find()) {
                        candidates.add(m.group(1).replaceAll("[`'\",.;)\\]]+$", ""));
                    }
                }
                // (b) version-shaped, in a script that defines the parser — the coupling is not
                //     always written as an assignment (apply-config.sh:580 narrates a pin instead).
                if (carriesTheParser) {
                    var m = versionShaped.matcher(line);
                    while (m.find()) {
                        candidates.add(m.group());
                        versionCandidates++;
                    }
                }
                for (String value : candidates) {
                    var key = rel + "|" + value;
                    if (value.contains("<") || value.contains(">") || value.contains("$")) {
                        derived.put(key, PinExample.PLACEHOLDER);
                        placeholders++;
                        continue;
                    }
                    var witness = liveWitness(variables, value, liveLines);
                    if (witness != null) {
                        derived.put(key, PinExample.REPRODUCED);
                        witnesses.put(key, witness);
                        reproducingScripts.add(rel);
                    } else if (derived.get(key) != PinExample.REPRODUCED) {
                        derived.put(key, PinExample.SWEPT);
                        witnesses.put(key, "named at " + rel + ":" + (i + 1)
                                + ", and no live line under src/test/java names "
                                + String.join("/", variables) + " and " + value + " together");
                    }
                }
            }
        }

        for (var e : derived.entrySet()) {
            if (e.getValue() == PinExample.REPRODUCED && !DECLARED_COUPLED_PIN_EXAMPLES.contains(e.getKey())) {
                problems.add("  NEW COUPLING, undeclared: " + e.getKey()
                        + " — reproduced at " + witnesses.get(e.getKey()));
            }
        }
        for (var key : DECLARED_COUPLED_PIN_EXAMPLES) {
            if (!derived.containsKey(key)) {
                problems.add("  GONE from the scripts: " + key + " is declared as a coupling and no"
                        + " ops script's prose carries that example any more — it was swept, reworded"
                        + " or deleted while its fixture stayed behind");
            } else if (derived.get(key) != PinExample.REPRODUCED) {
                problems.add("  NOT REPRODUCED: " + key + " is declared as a coupling but "
                        + witnesses.get(key));
            }
        }

        // Floors. Two of them — `reproduced` and `reproducingScripts` — sit EXACTLY at the live
        // value, so they refuse any shrink at all, not merely an empty or one-sided population: a
        // correct, coordinated retirement (prose and fixture moved together, entry dropped) reds
        // here and forces a deliberate edit. That is the intent, and saying it as "empty or
        // one-sided" would understate what the next reader is about to hit. `swept` (floor 5
        // against a live 14) and the arm-(b) candidate floor (20 against a live 36) have real
        // margin and refuse only a predicate that stopped matching.
        var reproduced = derived.values().stream().filter(v -> v == PinExample.REPRODUCED).count();
        var swept = derived.values().stream().filter(v -> v == PinExample.SWEPT).count();
        if (scripts.size() < 12 || tests.size() < 50) {
            problems.add("  FLOOR: scanned " + scripts.size() + " ops scripts and " + tests.size()
                    + " test sources — the walk found far less than this repository has, so the"
                    + " partition below was computed over the wrong tree");
        }
        if (variables.size() != 1) {
            problems.add("  FLOOR: read_image_tag() parses " + variables + " — the population is"
                    + " anchored on the variable the parser itself reads, so an empty or widened"
                    + " set means the anchor moved, not that there are no examples");
        }
        if (versionCandidates < 20) {
            problems.add("  FLOOR: arm (b) matched " + versionCandidates + " version-shaped tokens in"
                    + " the comments of the scripts that define read_image_tag. Those two carry dozens"
                    + " of measurement provenances, so a small number means the pattern stopped"
                    + " matching and the couplings written as NARRATIVE rather than as an assignment"
                    + " (apply-config.sh:580) went unseen");
        }
        if (derived.size() < 15 || reproduced < 4 || swept < 5 || placeholders < 1
                || reproducingScripts.size() < 2) {
            problems.add("  FLOOR: " + derived.size() + " examples (" + reproduced + " reproduced across "
                    + reproducingScripts.size() + " script(s), " + swept + " swept, " + placeholders
                    + " placeholders). The discriminator must stay able to answer BOTH ways on the"
                    + " live population, so the reproduced-side floors sit AT the live value and"
                    + " refuse any shrink — including a correct, coordinated retirement, which is"
                    + " meant to be a deliberate edit here and not a quiet one");
        }
        // GRANULARITY CONTROL. The member is (script, value) while the witness is found by value
        // alone, so one fixture line would answer for two scripts' couplings. Scoped to the
        // REPRODUCED members on purpose: a SWEPT one has no witness to share, and two scripts
        // recording the SAME MEASUREMENT (`29.2.1`, `6.4` and `v5.1.0` are each in both today) is
        // the normal, correct state this control must not mistake for a shared witness.
        var coupled = derived.entrySet().stream()
                .filter(e -> e.getValue() == PinExample.REPRODUCED).map(Map.Entry::getKey).toList();
        if (coupled.size() != coupled.stream().map(k -> k.substring(k.indexOf('|') + 1))
                .distinct().count()) {
            problems.add("  GRANULARITY: two ops scripts are now coupled to the same value, so ONE"
                    + " fixture line satisfies both of their comments and either could be swept"
                    + " with this seal still green. Find the witness per script, or declare why one"
                    + " fixture documents both");
        }

        assertThat(problems)
                .withFailMessage("""

                        A concrete %s=… example in an ops script's PROSE is not a published-tag claim. It is
                        the SHAPE of an .env line that script's own read_image_tag() parses, and a test
                        reproduces that exact string to document it -- so MOVE BOTH OR NEITHER. If you are
                        sweeping version strings onto the current release line, these are not among them.
                        (What is checked is co-occurrence: the variable and the value on ONE live line.)
                        Nothing else holds the pair: hamstrack-config-drift.sh:255 sits between write_metrics()
                        (closes 241) and read_image_tag() (opens 272), so the one text comparison between the
                        two scripts cannot see it (HD-335).

                        Only the COUPLINGS are declared (DECLARED_COUPLED_PIN_EXAMPLES). An example a fixture
                        reproduces must be named there; one nothing reproduces -- a release line a reader
                        copies, or a metavariable -- is deliberately not, so a correct sweep of THOSE stays
                        green. Which side you are on is re-derived every run:
                          GONE            the prose example vanished while its fixture stayed. You swept it:
                                          put it back, or change the fixture in the SAME edit and re-declare.
                          NOT REPRODUCED  the prose is still there and no live line names the variable and
                                          the value TOGETHER any more. Three repairs, in order of how often
                                          it is each: put the two back on one physical line (a fixture split
                                          across lines, or built by concatenation, reads as absent here --
                                          the witness text below says which line was looked at); restore the
                                          fixture if it was deleted or renamed; or drop the entry and accept
                                          that the comment is now an unproven claim.
                          NEW COUPLING    a fixture now depends on prose nobody recorded. Declare it, so the
                                          next sweeper is told before they edit rather than after.
                        Re-point the scan rather than deleting an entry. The reproduced-side floors sit at
                        the live value and refuse ANY shrink, including a correct retirement.

                        %s

                        The whole derived population, for comparison:
                        %s
                        """, String.join("/", variables), String.join("\n", problems),
                        partition(derived, witnesses))
                .isEmpty();
    }

    /**
     * Every derived member with its status and its witness. Printed on failure because the honest
     * place for a live count — and for the set an exemption admits — is the message that computes
     * it, never a number in a javadoc beside the list.
     */
    private static String partition(Map<String, PinExample> derived, Map<String, String> witnesses) {
        var sb = new StringBuilder();
        derived.forEach((key, kind) -> sb.append("  ").append(kind).append("  ").append(key)
                .append(witnesses.containsKey(key) ? "  <- " + witnesses.get(key) : "")
                .append('\n'));
        return sb.toString();
    }

    /** Every file under {@code root} with the given suffix, in a stable order. */
    private static List<Path> filesUnder(Path root, String suffix) throws IOException {
        assertThat(root)
                .withFailMessage("%s was not found — this seal reads the repository's own tree, so it "
                        + "must run from the module root", root.toAbsolutePath())
                .isDirectory();
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(suffix))
                    .sorted()
                    .toList();
        }
    }

    /**
     * The variable {@code read_image_tag} itself parses, read out of the function body rather than
     * written down here: the examples in prose are examples OF THAT PARSE, so the anchor has to
     * come from the parse. Reuses {@link ScriptHarness#functionBody}, which is the extraction the
     * neighbouring seals already trust.
     */
    private static Set<String> pinVariables(List<Path> scripts) throws IOException {
        var found = new HashSet<String>();
        for (Path script : scripts) {
            if (!Files.readString(script, StandardCharsets.UTF_8).contains("read_image_tag() {")) {
                continue;
            }
            var m = Pattern.compile("([A-Z][A-Z0-9_]{2,})\\[\\[:space:\\]\\]\\*=")
                    .matcher(ScriptHarness.functionBody(script, "read_image_tag"));
            while (m.find()) {
                found.add(m.group(1));
            }
        }
        return found;
    }

    /** One line of a test source that is not javadoc and not a {@code //} comment. */
    private record TestLine(String ref, String content) {}

    /**
     * Every non-prose line of every test source, read once. {@code //}, {@code /*} and continuation
     * {@code *} lines are dropped because a value a test merely TALKS about is not a fixture.
     *
     * <p>What this cannot see: a line inside a text block is live, so a value named only in a
     * failure message would read as reproduced. Measured example of exactly that shape —
     * {@code ConfigDriftContainerOracleTest:1328} carries {@code Engine 25.0.16} inside a
     * {@code withFailMessage} block while {@code ApplyConfigVerifyPhaseTest:2647} carries
     * {@code "version": "11.5.2"} inside a FIXTURE block, and no syntactic property separates
     * them. Neither value is in this population, and the witness line is printed in the failure
     * rather than merely counted so that a reader can see which kind it is.
     */
    private static List<TestLine> liveTestLines(List<Path> tests) throws IOException {
        var out = new ArrayList<TestLine>();
        for (Path test : tests) {
            var lines = Files.readAllLines(test, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                var trimmed = lines.get(i).strip();
                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                    continue;
                }
                out.add(new TestLine(posixRelative(test) + ":" + (i + 1), lines.get(i)));
            }
        }
        return out;
    }

    /**
     * The first live test line that names the pin variable and this exact value, or {@code null}.
     *
     * <p>The value is matched as a whole token: {@code 0.18} must not be answered by
     * {@code 0.18.0}, and {@code 6.3} must not be answered by the inside of {@code v0.16.3} —
     * which is how a containment test over version-shaped strings reads as coverage. The variable
     * must be on the same line, so a value that merely occurs somewhere in a test is not a witness.
     */
    private static String liveWitness(Set<String> variables, String value, List<TestLine> lines) {
        var exact = Pattern.compile("(?<![\\w.\\-])" + Pattern.quote(value) + "(?![\\w.\\-])");
        for (TestLine line : lines) {
            for (String variable : variables) {
                if (line.content().contains(variable) && exact.matcher(line.content()).find()) {
                    return line.ref();
                }
            }
        }
        return null;
    }

    /** Forward slashes, relative to the module root: the spelling the declaration is keyed on. */
    private static String posixRelative(Path p) {
        return p.toString().replace('\\', '/');
    }

    // --- harness ---------------------------------------------------------------------

    private static void expect(List<String> failures, String what, boolean ok, Result r) {
        if (!ok) {
            failures.add("\n  - " + what + "\n    (exit " + r.exit() + ") " + r.output().strip());
        }
    }

    /** A scratch box and the release tree that is applied to it. */
    private record Deployment(Path box, Path src, Path bin, Path dockerLog) {
        String stamp() throws IOException {
            var p = box.resolve(".deployed-image-tag");
            return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8).strip() : null;
        }

        String dockerCalls() throws IOException {
            return Files.exists(dockerLog) ? Files.readString(dockerLog, StandardCharsets.UTF_8) : "";
        }
    }

    private record Result(int exit, String output) {}

    private Deployment deployment(String name, String envFile, String stamp) throws IOException {
        return deployment(name, envFile, stamp, "docker-compose.prod.yml\nobservability/\n");
    }

    private Deployment deployment(String name, String envFile, String stamp, String manifest) throws IOException {
        var root = work.resolve(name);
        var box = root.resolve("box");
        var src = root.resolve("release");
        var bin = root.resolve("bin");
        Files.createDirectories(box);
        Files.createDirectories(src.resolve("ops/deploy"));
        Files.createDirectories(src.resolve("observability/grafana"));
        Files.createDirectories(bin);

        write(src.resolve("ops/deploy/synced-paths.txt"), manifest);
        write(src.resolve("docker-compose.prod.yml"), "services: {}\n");
        write(src.resolve("observability/grafana/grafana.ini"), "[server]\n");
        write(box.resolve(".env"), envFile);
        if (stamp != null) {
            write(box.resolve(".deployed-image-tag"), stamp + "\n");
        }

        // The stubs. `docker` records every invocation — which is how the bind-mount restart
        // is observed — and answers `ps -q` with a container id so that step 7b has something
        // to restart. `flock` is stubbed because Git Bash has none; each case owns its own
        // directory, so there is nothing to serialise against.
        writeStub(bin.resolve("docker"), """
                #!/usr/bin/env bash
                printf '%s\\n' "$*" >> "$DOCKER_LOG"
                case " $* " in *" ps -q "*) echo fake-cid ;; esac
                exit 0
                """);
        writeStub(bin.resolve("flock"), "#!/usr/bin/env bash\nexit 0\n");

        return new Deployment(box, src, bin, root.resolve("docker.log"));
    }

    private Result run(Deployment d, String... flags) throws Exception {
        return run(d, Map.of(), flags);
    }

    private Result run(Deployment d, Map<String, String> extraEnv, String... flags) throws Exception {
        // The skip is here rather than in @BeforeAll so that the one seal in this class which
        // reads files instead of running them still runs everywhere.
        ScriptHarness.assumeWithWitness("apply-config-pin-guard", bash != null,
                "no bash on PATH (and no Git for Windows bash.exe) — the tests that drive the real "
                        + "ops/deploy/apply-config.sh run on CI and on any POSIX machine, and skip only "
                        + "on a Windows box without Git Bash");
        assertThat(SCRIPT)
                .withFailMessage("ops/deploy/apply-config.sh was not found at %s — this test drives the "
                        + "repository's own copy, so it must run from the module root", SCRIPT.toAbsolutePath())
                .isRegularFile();
        assertStubsAreExecutable(d.bin());

        var cmd = new ArrayList<String>();
        cmd.add(bash);
        cmd.add(posix(SCRIPT));
        cmd.add(posix(d.src()));
        cmd.add(posix(d.box()));
        cmd.add("testsha");
        cmd.addAll(List.of(flags));

        var pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        var env = new LinkedHashMap<>(pb.environment());
        env.put("PATH", d.bin().toAbsolutePath() + java.io.File.pathSeparator + env.getOrDefault("PATH", ""));
        env.put("DOCKER_LOG", posix(d.dockerLog()));
        // Only the prod file: the observability compose file is not in this scratch release
        // tree, and the point being tested is not the "listed file absent is skipped" one.
        env.put("COMPOSE_FILES", "docker-compose.prod.yml");
        env.putAll(extraEnv);
        pb.environment().clear();
        pb.environment().putAll(env);

        var p = pb.start();
        var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(120, TimeUnit.SECONDS))
                .withFailMessage("apply-config.sh did not finish within 120s — output so far:\n%s", out)
                .isTrue();
        return new Result(p.exitValue(), out);
    }

    private static void write(Path p, String content) throws IOException {
        Files.createDirectories(p.getParent());
        // LF, always: a shebang line ending in CR makes bash report `bash\r: not found`.
        Files.writeString(p, content.replace("\r\n", "\n"), StandardCharsets.UTF_8);
    }

    /**
     * Writes a file that the script is meant to RUN, rather than read. Every such file needs
     * the execute bit on any filesystem that has one — {@link Files#writeString} leaves mode
     * 644 under the usual umask, and PATH lookup skips a file it cannot execute instead of
     * failing on it. See {@link #assertStubsAreExecutable}, which is what makes forgetting
     * this loud.
     */
    private static void writeStub(Path p, String content) throws IOException {
        write(p, content);
        if (!supportsPosixPermissions(p)) {
            // NTFS and friends: no execute bit exists to set, and bash runs the file from its
            // shebang. Nothing to do, and nothing this platform can prove either.
            return;
        }
        var perms = new HashSet<>(Files.getPosixFilePermissions(p));
        perms.add(PosixFilePermission.OWNER_EXECUTE);
        Files.setPosixFilePermissions(p, perms);
    }

    /**
     * <strong>Every file this class puts on the script's {@code PATH} must be executable</strong>
     * — not the two it happens to put there today. The check is here, at the moment {@code PATH}
     * is composed, so a stub added later by any route is covered without anyone remembering this
     * rule; and it is phrased as a category because the failure it stops does not announce which
     * name it is about.
     *
     * <p>What it stops: a shell does not fail on a non-executable file found on {@code PATH}, it
     * skips it and keeps searching, so the name resolves to whatever the machine really has. A
     * runner has a real {@code docker}; a scratch directory is not a Compose project; the script
     * then refuses, correctly, for a reason that reads as a defect in the script. That is a
     * five-failure red build whose every message points away from the cause, which is why this
     * fails as a STUB problem before the script is launched at all.
     */
    private static void assertStubsAreExecutable(Path bin) throws IOException {
        if (!supportsPosixPermissions(bin)) {
            return;
        }
        try (var entries = Files.list(bin)) {
            for (Path stub : entries.toList()) {
                assertThat(Files.isExecutable(stub))
                        .withFailMessage("""

                                THIS IS A STUB PROBLEM, NOT A SCRIPT FAILURE. %s is on the PATH this test \
                                hands to ops/deploy/apply-config.sh, and it is not executable on this \
                                filesystem (mode %s).

                                A shell SKIPS a non-executable file during PATH lookup and keeps searching, \
                                so the name resolves to whatever this machine really has -- on CI, the \
                                runner's real docker, pointed at a scratch directory that is not a Compose \
                                project. The script then refuses, correctly, and the failure reads as a \
                                defect in apply-config.sh several steps away from the stub that was never \
                                run. Every file this class puts on that PATH needs the execute bit wherever \
                                the filesystem has one: write it with writeStub(...), never with write(...) \
                                or Files.writeString, which leave mode 644.""",
                                stub.getFileName(), Files.getPosixFilePermissions(stub))
                        .isTrue();
            }
        }
    }

    /** True where the store can express an execute bit at all — false on NTFS. */
    private static boolean supportsPosixPermissions(Path p) throws IOException {
        return Files.getFileStore(p).supportsFileAttributeView(PosixFileAttributeView.class);
    }

    private static String read(Path p) throws IOException {
        return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : "";
    }

    /** Forward slashes: Git Bash accepts {@code C:/x/y}, and a backslash is an escape. */
    private static String posix(Path p) {
        return p.toAbsolutePath().toString().replace('\\', '/');
    }

    private static String findBash() {
        var name = System.getProperty("os.name").toLowerCase().contains("win") ? "bash.exe" : "bash";
        for (String dir : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            try {
                var candidate = Path.of(dir).resolve(name);
                if (Files.isExecutable(candidate)) {
                    return candidate.toString();
                }
            } catch (RuntimeException ignored) {
                // A PATH entry that is not a legal path on this platform is not a bash.
            }
        }
        for (String fallback : List.of("C:\\Program Files\\Git\\bin\\bash.exe", "/bin/bash", "/usr/bin/bash")) {
            if (Files.isExecutable(Path.of(fallback))) {
                return fallback;
            }
        }
        return null;
    }
}
