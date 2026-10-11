package com.hamstrack.ops;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.hamstrack.ops.OpsYaml.map;
import static com.hamstrack.ops.OpsYaml.parse;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>HD-321 &mdash; the container-free install path hands over every behaviour the container
 * supplies, and the list is enumerated from the container rather than remembered.</strong>
 *
 * <h2>Why this exists</h2>
 * {@code docs/self-hosting.md} § <em>Without Docker</em> tells an operator what they take on when
 * they run the published JAR instead of the image: supervision, the heap bound, the JVM locale
 * pins, the stop grace period, the healthcheck, the memory ceiling, the attachment volume, log
 * rotation. That list is <strong>prose about a category</strong>, and this release line is twelve
 * tickets of exactly that shape &mdash; a list that was complete when it was written and was never
 * re-read against the tree. The failure mode is specific and silent to every other test here: a
 * {@code stop_signal:}, a second {@code -XX:} flag or a new {@code logging:} block is added to the
 * container, every compose and install test stays green, and the hand-over list is one item short
 * for whoever reads it next. Nothing in that operator's experience says so; they simply do not do
 * the thing nobody told them about.
 *
 * <p>So the population is read out of the container on every run, and the section must name each
 * member. <strong>What "name" means here is deliberately weak</strong> &mdash; the member's own
 * token appears somewhere in the section &mdash; because the strong version ("the sentence about it
 * is correct") is a reader's job and a rule that pretended to do it would be a rule that reds on
 * correct prose. This refuses an <em>absence</em>, which is the half a scan can have.
 *
 * <h2>The two populations, and the unit each is keyed on</h2>
 * <ul>
 *   <li><strong>The container's own behaviour keys</strong> &mdash; the keys of the {@code app}
 *       service of every shipped non-development compose file, minus {@link #NOT_A_HANDOVER}.
 *       Keyed on <strong>the key</strong> and not on (file, key): two stacks both declaring
 *       {@code restart:} is one thing to hand over, documented once, and charging it twice would
 *       demand the section say it twice. The files that declare it travel in the failure message
 *       instead, which is where a reader needs them.</li>
 *   <li><strong>The image's JVM flags</strong> &mdash; every {@code -D…} / {@code -XX…} in the
 *       {@code Dockerfile}'s {@code ENTRYPOINT}, {@code CMD} or options variables (the directives
 *       {@link #JVM_DIRECTIVES} names). These are the members a compose-only scan cannot
 *       see, and they are the ones that cost an operator most: the heap percentage is
 *       <em>wrong</em> outside a cgroup rather than merely absent, and the locale pins decide
 *       whether a boot refusal is in a language the operator can search for.</li>
 * </ul>
 *
 * <h2>The granularity control, and what it can and cannot do</h2>
 * The unit of this rule is the <em>behaviour</em>. The collapse worth guarding against is the
 * population being keyed on the <em>file</em> instead &mdash; one sentence citing
 * {@code deploy/dc/docker-compose.yml} answering for all six of its keys, which is how a
 * "deduplicate the table" tidy-up would read as a simplification. That control is live: there are
 * strictly more members than source files today, and the assertion says so.
 *
 * <p><strong>Stated rather than implied, because the project's rule is that a control which cannot
 * discriminate reads as coverage:</strong> no member of the first population shares a token with a
 * member of the second, so the distinctness assertion over the merged set is a tripwire for a
 * future overlap and is <em>not</em> evidence that the two keyings differ today. The live control
 * is the member-count-against-file-count one.
 *
 * <h2>What this cannot see</h2>
 * Whether the hand-over the section describes is the <em>right</em> one; whether the operator can
 * perform it; a behaviour the container supplies that is declared in neither the compose files nor
 * the {@code ENTRYPOINT} (the base image's own `WORKDIR`, its user, its CA bundle); and anything
 * about the published JAR asset, which does not exist until a tag is cut and is therefore a
 * numbered step of {@code docs/release-checklist.md} § <em>The install path, once per release
 * line</em>, held by its own {@code Measured} date.
 */
class ContainerHandoverClaimsTest {

    private static final Path DOCKERFILE = Path.of("Dockerfile");

    private static final Path GUIDE = Path.of("docs/self-hosting.md");

    /** The heading the hand-over lives under, and the level that ends it. */
    private static final String SECTION = "### Without Docker";

    /**
     * Keys of an {@code app} service that are <strong>not</strong> a hand-over, each with the
     * property that makes it one. Every entry is checked against the live files by
     * {@link #everyExcusedKeyIsOneAShippedStackStillDeclares} &mdash; an exemption excusing a key
     * no stack declares any more is a standing permission for whatever grows into that name next,
     * and reads as evidence that somebody checked.
     */
    private static final Map<String, String> NOT_A_HANDOVER = Map.of(
            "image", "which artefact runs - the JAR path names its own, and that is the section's subject",
            "env_file", "how values are delivered; on the JAR path the shell delivers them, which the "
                    + "section's `export` block is",
            "environment", "the values themselves - the section lists the mandatory set and says which "
                    + "of them the stack was pinning as a literal",
            "depends_on", "a startup ORDERING, which a single process has no equivalent of - but "
                    + "what the ordering waits FOR is not excused here: a one-shot service it "
                    + "depends on is a member of everyOneShotServiceOfAShippedStackIsNamedWhere"
                    + "ThatStackIsDocumented, which is a live check rather than this sentence");

    /**
     * Any {@code -…} argument of a directive that configures the JVM, captured up to the {@code =} so
     * the member is the <em>flag</em> and not the value: {@code -Xmx512m} is what the section tells an
     * operator to use <em>instead of</em> {@code -XX:MaxRAMPercentage=50.0}, so a rule keyed on the
     * whole argument would be satisfied by the very sentence that replaces it.
     *
     * <p><strong>Not {@code -(?:XX:|D)[A-Za-z.:]+=}, which is what this was and which could not see
     * half of its own category.</strong> That form required a value, so every boolean and every
     * {@code -X} flag was invisible to it: planting {@code -XX:+ExitOnOutOfMemoryError} in the
     * {@code ENTRYPOINT} left this class GREEN (measured, HD-321) &mdash; a derived population that
     * answered "absent" and "not applicable" with the same silence, inside the rule written to
     * notice exactly that.
     *
     * <p><strong>Nor is it quote-anchored, which was the second half of the same hole.</strong> The
     * form was {@code "(-[^"=]+)}, so it could only see a flag inside a JSON-array {@code ENTRYPOINT}
     * &mdash; a shell-form {@code ENTRYPOINT java -Xmx512m -jar app.jar} carried none of its flags
     * past it. A flag is any argument starting with {@code -}, quoted or bare; {@code -jar} is
     * excluded below because it names the artefact rather than configuring the JVM, and that
     * exclusion is one line of live code rather than a sentence.
     */
    private static final Pattern JVM_FLAG = ContainerJvm.JVM_FLAG;

    /**
     * The directives that put a flag into the running JVM. {@code ENTRYPOINT} and {@code CMD} are
     * the obvious half; the three environment variables are the half that made the old population a
     * check that could not fail.
     *
     * <p><strong>Why reading them beats refusing them.</strong> The old scan took only lines starting
     * {@code ENTRYPOINT}, so moving a flag into {@code ENV JAVA_TOOL_OPTIONS} took the population
     * from three members to two while {@link #MIN_JVM_FLAGS} stayed satisfied: the flag was still in
     * effect inside the container and no longer demanded of the hand-over, and adding one that way
     * was invisible unconditionally (measured, HD-321). Refusing such an {@code ENV} outright would
     * also red that relocation, but it would red a legitimate one too &mdash; this rule's subject is
     * <em>every flag the image applies</em>, not <em>how the image is written</em>, and the
     * Dockerfile's own comment already recommends {@code JAVA_TOOL_OPTIONS=-Xmx…} to an operator
     * raising the memory limit. Reading both keeps the member in the population wherever it is
     * declared, which is the property the hand-over needs.
     *
     * <p><strong>The list itself moved to {@link ContainerJvm} in HD-343, with the parser that reads
     * it.</strong> That ticket added a second reader of this same {@code Dockerfile} which took only
     * {@code ENTRYPOINT} &mdash; i.e. it reintroduced, one class over, the exact defect the
     * paragraph above records fixing. Two readers of one file now share one parser instead of
     * agreeing by inspection.
     */
    private static final List<String> JVM_DIRECTIVES = ContainerJvm.JVM_DIRECTIVES;

    /**
     * Collapse floors, not counts. Each exists for one case: a derivation that stops finding
     * anything passes a presence rule vacuously, and that is the one shape this class could fail
     * to fail. They are deliberately far below the real populations, so that an ordinary edit never
     * touches them, and the failure messages print the sets rather than the numbers.
     *
     * <p>No count here, deliberately: the two this javadoc used to carry were both wrong by the time
     * anybody read them back (they said six behaviour keys and three sources; a real parse over the
     * shipped stacks answers seven and four), and nothing asserted either &mdash; which is this
     * repository's own "a number goes stale one entry before the list does", inside the control
     * written against that shape. The only honest place for a live count is a failure message, which
     * computes it.
     */
    private static final int MIN_BEHAVIOUR_KEYS = 4;

    /** @see #MIN_BEHAVIOUR_KEYS */
    private static final int MIN_JVM_FLAGS = 2;

    @Test
    void everyBehaviourTheContainerSuppliesIsHandedOverWhereTheJarPathIsDocumented() {
        Map<String, List<String>> behaviours = behaviourKeys();
        List<String> flags = jvmFlags();

        assertThat(behaviours.keySet())
                .withFailMessage("""
                        THE CONTAINER'S BEHAVIOUR KEYS COULD NOT BE READ, so this rule has nothing to
                        demand of the hand-over section and would pass whatever that section says.

                        Found: %s
                        Stacks examined: %s

                        The population is the keys of the `app` service of every shipped
                        non-development compose file, minus the ones NOT_A_HANDOVER excuses. An empty
                        or tiny set means the service was renamed, the files moved, or the parse
                        stopped reaching them - teach the derivation rather than lowering this
                        floor.""",
                        behaviours, shippedStacks())
                .hasSizeGreaterThanOrEqualTo(MIN_BEHAVIOUR_KEYS);

        assertThat(flags)
                .withFailMessage("""
                        THE IMAGE'S JVM FLAGS COULD NOT BE READ out of %s, so the members that cost an
                        operator most - the heap percentage and the locale pins - are demanded of
                        nothing.

                        Found: %s
                        Directives read: %s

                        Every `-…` argument of those directives is a member, quoted or bare,
                        continuation lines joined. If the Dockerfile grew another way to put a flag
                        into the JVM, add it to ContainerJvm.JVM_DIRECTIVES (shared with the other
                        reader of this file, JvmBoundsTest): a flag this scan stops seeing is still
                        in effect in the container and silently stops being demanded of the hand-over,
                        which is exactly the hole an ENTRYPOINT-only scan had.""",
                        DOCKERFILE, flags, JVM_DIRECTIVES)
                .hasSizeGreaterThanOrEqualTo(MIN_JVM_FLAGS);

        String section = handoverSection();
        var unhanded = new ArrayList<String>();
        behaviours.forEach((key, stacks) -> {
            if (!section.contains(key)) {
                unhanded.add("  `%s` - declared by %s, named nowhere in the hand-over".formatted(key, stacks));
            }
        });
        for (String flag : flags) {
            if (!section.contains(flag)) {
                // "applied by the image", not "in the ENTRYPOINT": since the population also reads
                // ENV JAVA_TOOL_OPTIONS and friends, naming one directive would misdescribe a flag
                // declared in another - and this line is what the reader goes looking for.
                unhanded.add("  `%s` - applied by the image (%s), named nowhere in the hand-over"
                        .formatted(flag, JVM_DIRECTIVES));
            }
        }

        assertThat(unhanded)
                .withFailMessage("""
                        THE CONTAINER DOES SOMETHING THE CONTAINER-FREE PATH DOES NOT HAND OVER.

                        %s

                        Behaviour keys and the stacks declaring them: %s
                        The image's JVM flags: %s

                        `%s` in %s is the list an operator reads before deciding to run the JAR, and
                        it is only worth reading while it is complete. A member missing from it is
                        not a gap the operator can notice: they do not fail to do something they
                        were told about, they fail to do something nobody mentioned - no restart
                        policy, an unbounded heap, a management port on every interface.

                        Add a row naming the key or the flag, with what the operator does instead.
                        If the thing genuinely has no equivalent outside a container, that is an
                        entry in NOT_A_HANDOVER with the property that makes it one - not silence.""",
                        String.join("\n", unhanded), behaviours, flags, SECTION, GUIDE)
                .isEmpty();

        // THE GRANULARITY CONTROL, and it is the LIVE one: the collapse this rule has to survive is
        // its population being keyed on the FILE rather than on the behaviour, which is what a
        // "the table repeats itself, cite the compose file once" tidy-up produces while reading as a
        // simplification. Strictly more members than sources is what makes the two keyings different
        // answers, and it is true today. No pair is written here: the assertion computes both
        // numbers, and the one this line used to carry was the exact stale pair the javadoc on
        // MIN_BEHAVIOUR_KEYS was corrected for ninety lines above.
        var sources = new LinkedHashSet<String>();
        behaviours.values().forEach(sources::addAll);
        sources.add(DOCKERFILE.toString().replace('\\', '/'));
        int members = behaviours.size() + flags.size();
        assertThat(sources.size())
                .withFailMessage("""
                        THIS RULE HAS STOPPED ASKING ITS QUESTION PER BEHAVIOUR.

                        Members: %d (%s plus %s)
                        Sources: %s

                        With one member per source file the rule is satisfied by a section that cites
                        the files and hands nothing over - the shape the hand-over table existed to
                        replace. The unit is the behaviour; the files belong in the message.""",
                        members, behaviours.keySet(), flags, sources)
                .isLessThan(members);

        // The tripwire half. No behaviour key equals a JVM flag today, so this cannot discriminate
        // the two keyings and is NOT evidence that they differ - it fires only if a future member of
        // one population is spelled like a member of the other, at which point one would silently
        // answer for both.
        var merged = new LinkedHashSet<String>(behaviours.keySet());
        merged.addAll(flags);
        assertThat(merged)
                .withFailMessage("""
                        A BEHAVIOUR KEY AND A JVM FLAG NOW SHARE A NAME, so one mention in the
                        hand-over answers for both members.

                        Keys:  %s
                        Flags: %s

                        Key the two populations apart - prefix the member rather than dropping one of
                        them.""", behaviours.keySet(), flags)
                .hasSize(members);
    }

    /**
     * <strong>An exemption is a live check or it is a lie.</strong> Each key {@link #NOT_A_HANDOVER}
     * excuses must still be declared by a shipped stack: an entry naming a key nothing declares
     * excuses whatever grows into that name next, while reading as a decision somebody made.
     */
    @Test
    void everyExcusedKeyIsOneAShippedStackStillDeclares() {
        var declared = new LinkedHashSet<String>();
        for (String stack : shippedStacks()) {
            declared.addAll(appServiceKeys(stack));
        }
        assertThat(declared)
                .withFailMessage("NO app SERVICE KEYS WERE READ AT ALL from %s, so the exemptions "
                        + "below would all look dead and this rule would report the wrong defect.",
                        shippedStacks())
                .isNotEmpty();

        var dead = NOT_A_HANDOVER.entrySet().stream()
                .filter(entry -> !declared.contains(entry.getKey()))
                .map(entry -> "  `%s` - excused as: %s".formatted(entry.getKey(), entry.getValue()))
                .toList();

        assertThat(dead)
                .withFailMessage("""
                        AN EXEMPTION EXCUSES A KEY NO SHIPPED STACK DECLARES.

                        %s

                        Declared by the stacks (%s): %s

                        Delete the entry. If the key came back later it would be excused by a
                        sentence nobody re-read, which is how `latest` came to be permitted inside
                        the two templates whose own prose forbids it (HD-324).""",
                        String.join("\n", dead), shippedStacks(), declared)
                .isEmpty();
    }

    /**
     * <strong>The hand-over's {@code SPRING_PROFILES_ACTIVE} row makes a claim about a DEFAULT, and
     * this is the default.</strong>
     *
     * <p>That row used to say a profile typo "is silent: an install that lands on Cloud defaults
     * gets S3 storage it has no bucket for, open public signup and a 10 GB workspace quota". Both
     * halves were backwards. A literal {@code cloud} is the <em>loudest</em> outcome the application
     * has &mdash; {@code S3FileStorage} exits the boot naming {@code STORAGE_S3_BUCKET}. Unset or
     * misspelled reads <em>no profile file at all</em> and lands on the BASE defaults, a third
     * configuration no shipped stack runs: it boots clean on local storage, and
     * {@code app.registration.public-signup-enabled} resolves <strong>true</strong> &mdash; public
     * self-registration open on a self-hosted install, which is the one posture
     * {@code application-dc.properties} exists to close.
     *
     * <p>So the row now names that as the quiet outcome, and a row naming a default needs the
     * default held: this asserts the resolution rather than the file, because the claim is about
     * what an install <em>gets</em>, and it is the direction nobody would re-check. <strong>It
     * reds in both directions on purpose.</strong> If the base default is ever flipped to
     * {@code false} the row's warning becomes false the other way round and should be rewritten,
     * not quietly kept; if {@code dc} stops being the thing that closes signup, the same.
     *
     * <p>Not a {@code @SpringBootTest}: the question is what the configuration files resolve to with
     * no profile, which {@link ConfigDataApplicationContextInitializer} answers over the real
     * {@code application.properties} without a datasource, a web server or any autoconfiguration.
     */
    @Test
    void theUnsetProfileLandsOnOpenPublicSignup() {
        assertThat(List.of(String.valueOf(System.getenv("SPRING_PROFILES_ACTIVE")),
                        String.valueOf(System.getenv("PUBLIC_SIGNUP_ENABLED"))))
                .withFailMessage("""
                        THIS MEASUREMENT IS ABOUT THE FILES, AND THE ENVIRONMENT IS OVERRIDING THEM.
                        SPRING_PROFILES_ACTIVE=%s, PUBLIC_SIGNUP_ENABLED=%s are set in this shell,
                        and an environment variable outranks application.properties - so a pass here
                        would be a measurement of your shell. Unset both and run again.""",
                        System.getenv("SPRING_PROFILES_ACTIVE"), System.getenv("PUBLIC_SIGNUP_ENABLED"))
                .containsOnly("null");

        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .run(context -> {
                    var environment = context.getEnvironment();
                    assertThat(environment.getActiveProfiles())
                            .withFailMessage("""
                                    A PROFILE IS ACTIVE IN THIS CONTEXT, so what follows is not the
                                    unset-profile configuration the hand-over row describes: %s""",
                                    List.of(environment.getActiveProfiles()))
                            .isEmpty();
                    assertThat(environment.getProperty("app.registration.public-signup-enabled"))
                            .withFailMessage("""
                                    THE UNSET-PROFILE DEFAULT FOR PUBLIC SELF-REGISTRATION HAS MOVED,
                                    and `%s` in %s says what it is.

                                    Resolved with no active profile: %s

                                    The row's quiet half is "unset or misspelled boots clean with
                                    public signup OPEN". If this now resolves `false`, that warning
                                    is wrong in the other direction and the row has to be rewritten
                                    - the mistake would then be loud in neither direction and
                                    harmless in one. If it resolves to nothing at all, the property
                                    moved or a profile file took over the base default, and the
                                    whole paragraph about a third configuration is stale.

                                    Do not delete this assertion to make a profile change pass: the
                                    document is the thing that has to change with it.""",
                                    SECTION, GUIDE,
                                    environment.getProperty("app.registration.public-signup-enabled"))
                            .isEqualTo("true");
                });
    }

    /**
     * <strong>Every one-shot service of a shipped stack is named where that stack is
     * documented.</strong>
     *
     * <p>The {@code depends_on} exemption above used to be excused as "an ordering between two
     * containers; a single process has no equivalent to hand over, and the database it waits for is
     * its own requirement bullet". That was exact for {@code deploy/dc/}, which declares one
     * condition ({@code postgres: service_healthy}), and false for {@code deploy/cloud/}, which
     * declares two &mdash; the second being {@code minio-bucket: service_completed_successfully},
     * whose whole job is {@code mc mb --ignore-existing}. That is not an ordering between peers: it
     * is a <strong>provisioning step</strong> with a mandatory JAR-path equivalent, and the
     * application turns its absence into a boot refusal ({@link
     * com.hamstrack.common.storage.S3FileStorage} naming {@code STORAGE_S3_BUCKET}) or, with a name
     * but no bucket, into a 500 on the first upload long after the install looked successful.
     *
     * <h2>Why an arm rather than narrowing the exemption to {@code service_healthy}</h2>
     * Narrowing it would make {@code depends_on} a member of the first population &mdash; and that
     * one is keyed on the <em>key</em>, so the demand would be satisfied by the token
     * {@code depends_on} appearing anywhere in the hand-over section. A sentence about startup
     * ordering would then acquit the bucket, which is the thing an operator actually has to do. The
     * member a reader needs is the <em>job</em>, so the job is what this counts.
     *
     * <p>It also closes a hole the first population has by construction: that one reads the
     * {@code app} service only, so {@code preflight}, {@code minio} and {@code minio-bucket} were
     * never members of anything in this class.
     *
     * <h2>What "named where that stack is documented" means, and why not "in the hand-over"</h2>
     * The hand-over section is written for {@code dc} throughout, and a cloud-only job belongs in
     * the cloud guide. So a member passes on the hand-over section <em>or</em> on any publishable
     * document that names the stack's own compose file &mdash; the same attribution
     * {@code InstallClaimsTest} uses for published ports, which is also what makes a stack no
     * document names a reported offence rather than a skipped one. Naming is weak here for the
     * reason it is weak above: that the sentence around the name is <em>correct</em> is a reader's
     * job, and a rule pretending otherwise would red on correct prose.
     *
     * <h2>Granularity, stated rather than implied</h2>
     * The unit is (stack, service). Only one shipped stack declares one-shot services today, so the
     * key-collapse control below is a <strong>tripwire</strong> and not evidence that the two
     * keyings differ: it fires when two stacks come to share a job name, at which point one
     * mention would answer for both.
     */
    @Test
    void everyOneShotServiceOfAShippedStackIsNamedWhereThatStackIsDocumented() {
        var jobs = new LinkedHashMap<String, List<String>>();
        int members = 0;
        for (String stack : shippedStacks()) {
            List<String> oneShot = oneShotServices(stack);
            jobs.put(stack, oneShot);
            members += oneShot.size();
        }

        assertThat(members)
                .withFailMessage("""
                        NO ONE-SHOT SERVICE WAS FOUND IN ANY SHIPPED STACK, so this rule has nothing
                        to demand and would pass whatever the documents say.

                        Per stack: %s

                        A one-shot service is `restart: "no"` plus an `entrypoint:` or a `command:` -
                        a container that runs a script and exits, which is how a stack provisions
                        something before the app starts. `deploy/cloud/` ships two (`preflight`,
                        `minio-bucket`). If a stack genuinely stopped shipping one, that is a
                        deliberate edit here; if the shape changed, teach the derivation. NOTE: an
                        UNQUOTED `restart: no` is YAML 1.1 boolean false, which is why both spellings
                        count below - a quoted value is a string and an unquoted one is not.""",
                        jobs)
                .isPositive();

        var offences = new ArrayList<String>();
        var pairs = new ArrayList<String>();
        String section = handoverSection();
        jobs.forEach((stack, oneShot) -> {
            List<String> documents = documentsNaming(stack);
            for (String service : oneShot) {
                pairs.add(stack + " -> " + service);
                if (section.contains(service)) {
                    continue;
                }
                if (documents.isEmpty()) {
                    offences.add(("  `%s` in %s - no publishable document names that compose file, "
                            + "so the stack has no guide this job could be named in").formatted(service, stack));
                } else if (documents.stream().noneMatch(document -> read(Path.of(document)).contains(service))) {
                    offences.add("  `%s` in %s - named neither in the hand-over nor by %s"
                            .formatted(service, stack, documents));
                }
            }
        });

        assertThat(offences)
                .withFailMessage("""
                        A SHIPPED STACK PROVISIONS SOMETHING THE DOCUMENTS NEVER HAND OVER.

                        %s

                        One-shot services per stack: %s

                        A one-shot service is work the stack does FOR the operator before the
                        application starts - creating the bucket, refusing a value MinIO would
                        refuse later and less legibly. On the container-free path nobody does it,
                        and the application's own answer to the missing half is a boot refusal or a
                        500 on the first upload. `depends_on` is excused from the hand-over as an
                        ordering; what the ordering WAITS FOR is this rule, because the two are not
                        the same thing and the excuse used to cover both.

                        Name the job - in `%s` of %s if it applies to every mode, or in the guide of
                        the stack that ships it, with what the operator does instead.""",
                        String.join("\n", offences), jobs, SECTION, GUIDE)
                .isEmpty();

        assertThat(new LinkedHashSet<>(pairs))
                .withFailMessage("""
                        THIS RULE HAS STOPPED ASKING ITS QUESTION PER (STACK, SERVICE).

                        Members charged: %d
                        Distinct keys:   %s

                        Two stacks now ship a job of the same name, so one mention answers for both
                        - and the two can be different jobs with different hand-overs. Key the
                        population on the stack AND the service.""",
                        members, new LinkedHashSet<>(pairs))
                .hasSize(members);
    }

    // ============================================================ helpers

    /**
     * The one-shot services of a stack: {@code restart: "no"} (or the unquoted {@code no}, which
     * YAML 1.1 hands over as boolean {@code false}) together with an {@code entrypoint:} or a
     * {@code command:}. The restart policy alone would take in nothing; the script alone would take
     * in {@code postgres} and {@code minio}, which run for ever.
     */
    private static List<String> oneShotServices(String stack) {
        var services = new ArrayList<String>();
        map(map(parse(Path.of(stack))).get("services")).forEach((name, definition) -> {
            Map<String, Object> keys = map(definition);
            String restart = OpsYaml.text(keys.get("restart"));
            boolean runsOnce = "no".equals(restart) || "false".equals(restart);
            if (runsOnce && (keys.containsKey("entrypoint") || keys.containsKey("command"))) {
                services.add(name);
            }
        });
        return services;
    }

    /**
     * The publishable documents that name a stack's compose file, which is what makes one of them
     * that stack's own guide rather than a page that merely mentions the product.
     */
    private static List<String> documentsNaming(String stack) {
        var documents = new ArrayList<String>();
        for (Path file : PublishedCredentials.publishableFiles("*.md")) {
            String path = PublishedCredentials.repositoryPath(file);
            if (read(file).contains(stack)) {
                documents.add(path);
            }
        }
        return documents;
    }

    /** Every behaviour key, mapped to the stacks that declare it. */
    private static Map<String, List<String>> behaviourKeys() {
        var byKey = new LinkedHashMap<String, List<String>>();
        for (String stack : shippedStacks()) {
            for (String key : appServiceKeys(stack)) {
                if (!NOT_A_HANDOVER.containsKey(key)) {
                    byKey.computeIfAbsent(key, unused -> new ArrayList<>()).add(stack);
                }
            }
        }
        return byKey;
    }

    /**
     * The keys of a compose file's {@code app} service, parsed rather than grepped: these files are
     * mostly comments on purpose, and a {@code mem_limit:} inside one must not satisfy a check for
     * the setting (the reason {@link OpsYaml} exists).
     */
    private static List<String> appServiceKeys(String stack) {
        return List.copyOf(map(map(parse(Path.of(stack))).get("services")).entrySet().stream()
                .filter(service -> "app".equals(service.getKey()))
                .flatMap(service -> map(service.getValue()).keySet().stream())
                .toList());
    }

    /**
     * The shipped compose files carrying an {@code app} service: the installable stacks under
     * {@code deploy/} and the production stack. Development stacks are out through
     * {@link PublishedCredentials#isDevelopmentCompose} &mdash; the same live {@code .dev.} marker
     * {@code InstallClaimsTest} uses, rather than a second opinion about what a dev stack is.
     */
    private static List<String> shippedStacks() {
        var stacks = new ArrayList<String>();
        for (Path file : PublishedCredentials.publishableFiles(".")) {
            if (!file.getFileName().toString().matches("docker-compose.*\\.ya?ml")
                    || PublishedCredentials.isDevelopmentCompose(file)) {
                continue;
            }
            String path = PublishedCredentials.repositoryPath(file);
            if (!appServiceKeys(path).isEmpty()) {
                stacks.add(path);
            }
        }
        return stacks;
    }

    /**
     * Every JVM flag the image applies, in declaration order, from any directive in
     * {@link #JVM_DIRECTIVES} &mdash; continuation lines joined first, so an {@code ENTRYPOINT}
     * wrapped across two physical lines is one directive here rather than one matched line and one
     * invisible one.
     */
    private static List<String> jvmFlags() {
        var flags = new ArrayList<String>();
        for (String directive : logicalLines(read(DOCKERFILE))) {
            if (!configuresTheJvm(directive)) {
                continue;
            }
            var matcher = JVM_FLAG.matcher(directive);
            while (matcher.find()) {
                if (!"-jar".equals(matcher.group(1))) {       // the artefact, not a setting
                    flags.add(matcher.group(1));
                }
            }
        }
        return flags;
    }

    /**
     * Does this logical directive put flags into the running JVM?
     *
     * <p>{@code ENTRYPOINT} / {@code CMD} own their whole directive, so a prefix answers for them.
     * An {@code ENV} does not: one directive may set several variables, and the variable this rule
     * cares about need not be the first. A prefix test &mdash; which is what this was &mdash; reads
     * {@code ENV FOO=bar JAVA_TOOL_OPTIONS=-Xmx512m} as "not an ENV JAVA_TOOL_OPTIONS" and drops
     * every flag in it, which is the hole the {@code ENTRYPOINT}-only scan had, one shape over: the
     * flags are in effect in the container and silently stop being demanded of the hand-over. So the
     * variable is matched <strong>anywhere in the directive</strong>, as an assignment of its own
     * name (preceded by the directive keyword or whitespace, followed by {@code =}), which is also
     * what keeps {@code JAVA_TOOL_OPTIONS_EXTRA=} from answering for it.
     *
     * <p>The body moved to {@link ContainerJvm#configuresTheJvm} in HD-343 so that a second reader
     * of this {@code Dockerfile} uses this predicate rather than a narrower one of its own.
     */
    private static boolean configuresTheJvm(String directive) {
        return ContainerJvm.configuresTheJvm(directive);
    }

    /**
     * The Dockerfile's directives, with {@code \}-continued physical lines folded into one and
     * comments dropped. A directive spread over several lines is the ordinary way a long
     * {@code ENTRYPOINT} is written, and a line-at-a-time scan sees its tail as something else.
     *
     * <p>Body shared with {@link ContainerJvm#logicalLines} since HD-343, for the reason on
     * {@link #configuresTheJvm}.
     */
    private static List<String> logicalLines(String text) {
        return ContainerJvm.logicalLines(text);
    }

    /** The hand-over section: from its heading to the next {@code ## } heading. */
    private static String handoverSection() {
        String text = read(GUIDE);
        int start = text.indexOf(SECTION);
        assertThat(start)
                .withFailMessage("""
                        THE HAND-OVER SECTION IS GONE. Expected a heading starting '%s' in %s.

                        It is the only place this repository tells an operator what the container was
                        doing for them, and the release assets that make the path reachable are
                        published whether or not it exists (.github/workflows/build.yml). If the
                        heading moved, move this constant with it.""", SECTION, GUIDE)
                .isNotNegative();
        int end = text.indexOf("\n## ", start);
        return end < 0 ? text.substring(start) : text.substring(start, end);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + path, e);
        }
    }
}
