package com.hamstrack.common.architecture;

import com.hamstrack.common.security.CalledOnlyFrom;
import com.hamstrack.common.testsupport.Doors;
import com.hamstrack.common.testsupport.Population;
import com.hamstrack.common.testsupport.ProductionBytecode;
import com.hamstrack.common.testsupport.SourceCallSites;
import com.tngtech.archunit.core.domain.JavaClass;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>A member whose containment is a claim about who calls it has that claim executed</strong>
 * (HD-340).
 *
 * <p>Some members cannot protect themselves. A finder with no tenancy predicate answers across
 * every tenant; an append that stamps nothing is only as trustworthy as whoever assembled the row;
 * a reference minted from a bare id is a foreign key to whatever that id belongs to. In each case
 * the callee can see neither the request nor the tenant, so what keeps it safe is a fact about the
 * <em>call graph</em> — and this repository had been writing those facts in javadoc. A fact about
 * the call graph written in prose is held by whoever last read the prose, and the member it is
 * about has no symptom when it stops being true: an unscoped read answers, an unstamped row
 * inserts, a cross-tenant reference writes.
 *
 * <h2>What is asserted, and in which direction</h2>
 * <ol>
 *   <li>no production class reaches a marked member unless the member declares it — the rule;</li>
 *   <li><strong>no declared caller is one a web bean dispatches to</strong>, unless the member
 *       names that caller in {@code requestScopedCallers} — because the cheap way past (1) is not
 *       a new call site, it is widening {@code callers} by one word in a diff where the query is
 *       untouched and everything reads as documentation;</li>
 *   <li>no member declares a caller that no longer calls it — the <em>anti-vacuity</em> half,
 *       because a walk that silently returned nothing would satisfy (1) for ever;</li>
 *   <li>no marked name is overloaded — the unit control, since the walk is keyed on the method
 *       NAME and would otherwise answer about a method nobody marked;</li>
 *   <li>every caller-set claim still written in production prose names a live test — a secondary
 *       accusation, with the blind spots named on the arm itself.</li>
 * </ol>
 *
 * <h2>Why the population is a marker</h2>
 * The population is every member carrying {@link CalledOnlyFrom}, enumerated from production
 * bytecode, so it travels with a rename, a move or a rewording. The alternative — deriving the
 * population from the prose — loses a member the day somebody rewrites a sentence, and reports the
 * same green afterwards. That is not a hypothetical here: of the five members HD-340's review round
 * added, <strong>the prose arm as first written matched none</strong> (measured — the pre-widening
 * scan returned five blocks and not one of them belonged to those members), because their claims
 * are written as "Seeding only", "exist for the operator metrics", "the only load behind
 * RolePermissionCache" and "every caller is visible in one grep", none of which puts a caller word
 * beside an identity word. Two of the five are matched now, and the arm is still not the population
 * rule. It is the same split {@code Credential} and
 * {@code CredentialPropertiesCarryNoConstraintTest} use for a credential-bearing property, and (5)
 * is the half that is allowed to be incomplete.
 *
 * <h2>What this seal does not hold, stated so it is not mistaken for stronger</h2>
 * It is a <em>static call graph</em> question. A caller that reaches a marked member through
 * reflection, a Spring Data projection, or a proxy it obtained some other way is invisible here, as
 * is anything in {@code src/test} (the import is production-only on purpose: a test calling an
 * unscoped finder is how its behaviour gets pinned). And it cannot separate two overloads — (4)
 * refuses an overloaded name rather than quietly answering about the wrong one; such a member needs
 * a seal that matches on the parameter list, {@code MailAddressesThrottleKeyTest} being the worked
 * example in this tree.
 *
 * <p><strong>And (2) is one hop, not a reachability proof.</strong> A declared caller that no web
 * bean touches but a <em>service</em> on a request path does — {@code SprintScopeLedger} is exactly
 * that — is not refused. The transitive version was measured and discarded rather than skipped;
 * the figures, the relation that produced them and the date are on
 * {@link #REQUEST_DISPATCHED_CEILING}, which is deliberately the only place in this file that
 * states one.
 *
 * <p>No Spring context: ArchUnit over {@code target/classes} plus a read of the source tree, so it
 * fails at the commit that adds the caller rather than at the incident that follows it.
 */
class CalledOnlyFromSealTest {

    /**
     * 709 classes were imported on 2026-09-09 and the floor {@code ArchitectureRulesTest} holds the
     * same import at is 560. Repeated rather than shared because a floor is an assertion about the
     * population <em>this</em> file judges, and {@code ProductionBytecode} deliberately carries
     * none of its own: "found nothing" and "looked at nothing" print the same green line.
     */
    private static final int IMPORT_FLOOR = 560;

    /**
     * The marked members, measured 2026-10-24: the shadowed-key startup scan's unscoped read, the
     * sprint scope ledger's append, two bare-id role lookups, two install-wide storage aggregates
     * and the seeder's folded-email lookup. Equal to the count rather than below it, because at
     * this size a lower floor would let a member leave in silence, which is the one thing a floor
     * exists to prevent. Raise it when a member is added; lowering it is a decision about a member,
     * and the commit that lowers it names which.
     */
    private static final int MEMBER_FLOOR = 7;

    /**
     * {@code Doors.webBeans()} held 34 classes on 2026-09-09 and {@code ArchitectureRulesTest}
     * floors the same population at 30. Floored here too rather than trusted, because arm (2) is an
     * "is this caller in that set" question: an empty set admits every caller silently.
     */
    private static final int WEB_BEAN_FLOOR = 30;

    /**
     * Of the marked members, how many must still be refused outright rather than carrying a named
     * exemption. Six of seven on 2026-10-24 — one gauge supplier is exempted. A count rather than a
     * ratio because the population is small enough that a ratio would round an exemption away.
     */
    private static final int UNEXEMPTED_MEMBER_FLOOR = 6;

    /**
     * <strong>A ceiling, not a floor, and the only one in this file.</strong> Arm (2)'s predicate is
     * a <em>discriminator</em> — it has to separate request-dispatched classes from the rest — and a
     * predicate that grows to cover the tree stops discriminating while still passing every floor.
     * <p><strong>The numbers, and the relation that produced them</strong>, because a figure
     * without its relation is how this file came to carry a wrong one for a round. Measured
     * 2026-10-24 by this method, over ArchUnit's {@code getMethodCallsFromSelf} +
     * {@code getMethodReferencesFromSelf} from the {@code Doors.webBeans()} roots, counting
     * {@code com.hamstrack.*} only, against {@code Doors.productionClasses()} = 705: <strong>one
     * hop 122 (17.3%)</strong>, <strong>transitive closure 495 (70.2%)</strong>. So the transitive
     * predicate would hold two classes in three and discriminate nothing, which is why it was
     * rejected; a different relation (a different ArchUnit edge set, field accesses, constructor
     * calls) gives a different number, and any figure quoted without naming one is unreproducible.
     *
     * <p>The ceiling sits at 0.45: about 2.6× the live measurement, so an ordinary slice does not
     * trip it, and well under the transitive figure, so the arm cannot quietly become the
     * predicate that was rejected. It is a fraction rather than a count so it cannot drift with
     * the tree's size, and the live figure is computed by the assertion below on every run —
     * nothing here has to be re-typed when it moves.
     */
    private static final double REQUEST_DISPATCHED_CEILING = 0.45;

    /**
     * The claims this codebase writes in the shapes below: the two held by a bespoke seal
     * ({@code UserEmailFinderSealTest}, {@code MailAddressesThrottleKeyTest}), the ones held by
     * this seal, and the annotation's own javadoc. Floored well under that, because the subject is
     * prose and a legitimate rewording may take one out of the population; it is the collapse to
     * nothing that would make the arm vacuous. Deliberately no leading count: the live number is in
     * the floor's own failure message, which computes it, and a number written here would go stale
     * one entry before the list did.
     */
    private static final int CLAIM_FLOOR = 4;

    /** 3 413 comment blocks over 577 production files on 2026-10-23. */
    private static final int COMMENT_BLOCK_FLOOR = 2_500;

    /** 577 production and 353 test compilation units on 2026-10-23, measured by this arm. */
    private static final int MAIN_UNIT_FLOOR = 500;

    private static final int TEST_UNIT_FLOOR = 300;

    /**
     * Why a short population makes every arm here vacuous, and what its reader can do about it —
     * passed to {@link Population#floor(int, String, List)} rather than inherited, because the
     * standard causes are about a {@code Doors} population and would tell this reader to "fix
     * Doors", which is not a thing they can do (HD-301's defect, in a new place).
     */
    private static final String WHY_THE_POPULATION_MATTERS =
            "Every arm here asserts \"nothing offends\", so a population that stopped seeing "
            + "members reports clean for ever.";

    private static final List<String> POPULATION_CAUSES = List.of(
            "the import is stale or empty — `mvnw clean compile`, then re-run: the population is"
            + " read from target/classes, not from source;",
            "a member really lost its marker — if that was deliberate, lower MEMBER_FLOOR in the"
            + " SAME commit and name the member in that commit's message;",
            "@CalledOnlyFrom was renamed, moved or had its @Retention weakened below RUNTIME, in"
            + " which case every member is invisible here and nothing is checked.");

    /**
     * The shapes a caller-set claim is written in here, measured over the tree rather than
     * imagined: an identity ("the single caller is …", "no production caller"), or a prohibition
     * ("nothing request-scoped may call it", "no controller may insert here"). Matched against a
     * whole comment BLOCK and not a line, because the live claim this ticket came from has
     * "Nothing request-scoped may" ending one line and "call it" opening the next — a scan that
     * read lines would have found neither half and reported clean.
     *
     * <p>Deliberately NOT matching an obligation on the caller ("callers must chunk", "the caller
     * MUST flush"): those say what a caller has to do, not who may be one, and they are held by
     * the behaviour they describe rather than by a membership rule. Nor "may read"/"may write",
     * which in this tree say what a caller may do with <em>data</em> — measured 2026-10-23, adding
     * those two took the population from 5 blocks to 15, and all ten of the extras were about a
     * permission, a response field or an operand grammar rather than about who may call anything.
     *
     * <p><strong>The last two shapes were added by HD-340's review round, from members this set had
     * missed</strong> — which is the measured case for this arm being secondary rather than the
     * population rule. "Seeding only" and "visible in one grep" are both caller-set claims, and
     * neither puts a caller word beside an identity word.
     *
     * <p><strong>"and by nothing else" was tried as a third and withdrawn</strong>, measured
     * 2026-10-24: it is a caller-set claim in one place and an English intensifier in three others
     * — {@code ReportController} ("gated by project membership and nothing else"),
     * {@code WorkspaceRepository} (quoting a round-2 claim that was itself wrong) and
     * {@code RoleService} (about a comparand). Three accusations to catch one member is how an arm
     * teaches its readers to silence it with a pointer they do not believe in, and a pointer added
     * to stop a nuisance is worth less than no pointer at all. So a claim written that way is
     * invisible here, deliberately and on the record.
     */
    private static final List<Pattern> CLAIM_SHAPES = List.of(
            Pattern.compile("\\b(only|single|sole|one) caller (is|are)\\b"),
            Pattern.compile("\\bno production caller\\b"),
            Pattern.compile("\\bmay call\\b"),
            Pattern.compile("\\bmay insert\\b"),
            Pattern.compile("\\bseeding only\\b"),
            Pattern.compile("\\bvisible in one grep\\b"));

    /** A pointer in prose to the thing that holds the claim. */
    private static final Pattern NAMES_A_TEST = Pattern.compile("\\b([A-Z][A-Za-z0-9]*Test)\\b");

    /**
     * One marked member: the annotated element, the owner whose bytecode is walked, and the names
     * the walk is narrowed to — one for a method, and for a type every method it OFFERS, inherited
     * ones included (see {@link #offeredMethodsOf}). So a method that arrives on a marked interface
     * is covered on the day it arrives, whether somebody typed it or a supertype handed it over —
     * and on these interfaces the second is the dangerous way.
     */
    private record Contained(String key, Class<?> owner, Set<String> names, List<String> callers,
                             String because, String instead, List<String> requestScopedCallers) {
    }

    // ------------------------------------------------------------------ the rule

    /**
     * <strong>Nothing reaches a marked member but its declared callers.</strong>
     *
     * <p>Compared at CLASS granularity while the offence is PRINTED at site granularity, and the
     * asymmetry is deliberate: what makes a caller admissible is a property of the class (this one
     * is a process and not a request; that one is the writer that stamps the tenant), and a call
     * inside a lambda compiles into a synthetic method of its enclosing class — so a declaration
     * written at site granularity would name {@code lambda$inviteMember$12} and go stale on the
     * next edit to an unrelated lambda in the same file. A nested class of a declared caller counts
     * as that caller; anything else, including the declaring type itself, does not.
     */
    @Test
    void nothingReachesAContainedMemberButItsDeclaredCallers() {
        var offences = new LinkedHashMap<Contained, List<String>>();
        for (var member : containedMembers()) {
            var undeclared = callSitesOf(member).stream()
                    .filter(site -> !admits(member.callers(), ownerOf(site)))
                    .toList();
            if (!undeclared.isEmpty()) {
                offences.put(member, undeclared);
            }
        }

        assertThat(offences)
                .withFailMessage("%s", refusal(offences))
                .isEmpty();
    }

    /**
     * <strong>No declared caller is one a web bean dispatches to.</strong>
     *
     * <p>Arm (1) refuses a caller nobody declared. This one refuses the <em>declaration</em>, and it
     * is the half the rule is actually about: a contributor whose call does not compile reads the
     * refusal, sees "add its fully-qualified class name to that member's {@code @CalledOnlyFrom}",
     * and does exactly that. One word, a green build, and a member of workspace A reads workspace
     * B's field definitions — in a diff where the {@code @Query} is untouched and everything looks
     * like documentation. Without this arm the seal holds "no class but those listed calls it",
     * which is not what any of these members' javadoc claims.
     *
     * <p><strong>The predicate is one hop, and that is a measurement rather than a shortcut.</strong>
     * A caller is request-dispatched when it is a web bean or a class a web bean calls directly. The
     * obvious stronger predicate — reachable from a web bean at any depth — was computed and
     * discarded: it holds two production classes in three (figures and relation on
     * {@link #REQUEST_DISPATCHED_CEILING}), including {@code SprintScopeLedger},
     * {@code DataSeeder}, {@code RolePermissionCache} and {@code ProductMetrics}, all of which are
     * legitimate callers here. One hop is where the discrimination lives, and the ceiling holds it
     * there.
     *
     * <p><strong>Three assertions, and the third is the one that keeps an exemption honest.</strong>
     * A name in {@code requestScopedCallers} exempts a <em>class</em>, but the argument for writing
     * it is always about a <em>site</em> — "the call is a gauge supplier handed to the builder in
     * the constructor". Those coincide only while every call from that class happens at
     * construction time, so that is asserted rather than trusted: the day somebody calls the
     * member from an ordinary method of an exempted class, arm (1) is satisfied (it is declared),
     * the exemption is satisfied (the class is named), and nothing would be left to refuse an
     * unscoped read on a request path.
     *
     * <p>It also closes the hop the reviewer found on arm (1): {@code ShadowedFieldStartupScan} is a
     * {@code public} method on a {@code @Component} with zero production call sites, so "the startup
     * scan is the only caller" is an adjacency fact that a single new controller call could make
     * meaningless while arm (1) stayed green. The day a web bean calls it, the scan becomes
     * request-dispatched and this arm refuses it — directly, with no transitive walk.
     */
    @Test
    void noDeclaredCallerIsOneAWebBeanDispatchesTo() {
        var dispatched = requestDispatchedClasses();
        var offences = new LinkedHashMap<String, List<String>>();
        var staleExemptions = new LinkedHashMap<String, List<String>>();
        var requestReachableSites = new LinkedHashMap<String, List<String>>();
        for (var member : containedMembers()) {
            var exposed = member.callers().stream()
                    .filter(dispatched::contains)
                    .filter(caller -> !member.requestScopedCallers().contains(caller))
                    .toList();
            if (!exposed.isEmpty()) {
                offences.put(member.key(), exposed);
            }
            // An exemption is a live check or it is a lie: a name that is no longer declared, or
            // no longer request-dispatched, is a written decision whose subject has moved on.
            var stale = member.requestScopedCallers().stream()
                    .filter(caller -> !member.callers().contains(caller)
                                      || !dispatched.contains(caller))
                    .toList();
            if (!stale.isEmpty()) {
                staleExemptions.put(member.key(), stale);
            }
            if (!member.requestScopedCallers().isEmpty()) {
                var reachable = callSitesOf(member).stream()
                        .filter(site -> member.requestScopedCallers().contains(ownerOf(site)))
                        .filter(site -> !isConstructionTime(site))
                        .toList();
                if (!reachable.isEmpty()) {
                    requestReachableSites.put(member.key(), reachable);
                }
            }
        }

        assertThat(requestReachableSites)
                .withFailMessage("""

                        An exempted caller reaches this member from a method, not from its \
                        construction: %s

                        The exemption is granted to a CLASS; the argument for granting it is \
                        always about a SITE — "the call is a gauge supplier handed over in the \
                        constructor", "the reference is built once at startup". Those are the same \
                        statement only while every call from that class happens at construction \
                        time. The moment one does not, the class is request-dispatched AND \
                        exempted, arm (1) is satisfied because the caller is declared, and an \
                        unscoped member answers on a request path with nothing left to refuse it.

                        So either move the call back to the constructor — a supplier, a reference, \
                        a value captured once — or withdraw the exemption and route the request \
                        path through what `instead` names. A class-wide exemption on a site-wide \
                        argument is the shape that makes a seal read as coverage it does not have.

                        Construction time means #<init> or #<clinit>. Another ground for an \
                        exemption is a different attribute and a different check here, never a \
                        widening of this one.""", requestReachableSites)
                .isEmpty();

        assertThat(staleExemptions)
                .withFailMessage("""

                        A requestScopedCallers entry no longer describes anything: %s

                        Each name there exempts ONE declared caller from the arm below, on the \
                        grounds that a web bean dispatches to it and that is intended. A name that \
                        is no longer in `callers`, or no longer request-dispatched, has outlived \
                        its subject — and the next class to take that name would inherit an \
                        exemption nobody wrote about it. Delete the entry in the commit that made \
                        it untrue.""", staleExemptions)
                .isEmpty();

        assertThat(offences)
                .withFailMessage("""

                        A member declares a caller that a web bean dispatches to: %s

                        Arm (1) only asks whether a caller is listed, so widening `callers` by one \
                        word is all it takes to put an unscoped member on a request path — with the \
                        query untouched and the whole diff reading as documentation. That is the \
                        move this arm exists to refuse.

                        If the member really is safe to reach that way, say so where a reviewer \
                        cannot miss it: name that ONE caller in requestScopedCallers AND rewrite \
                        `because` to explain why a request may have this answer. Naming it exempts \
                        it alone — the next request-dispatched caller is refused again. If it is \
                        not safe — and for anything whose javadoc says "nothing request-scoped may \
                        call it", it is not — the caller belongs on the path named in `instead`.

                        "Dispatched to by a web bean" means a web bean or a class one calls \
                        directly. A caller further out on a request path is NOT refused here — \
                        reachability at any depth holds most of the tree and would refuse every \
                        legitimate member, so it is not the predicate.""",
                        offences)
                .isEmpty();

        // LAST, and the order is the point. The three assertions above each name a concrete
        // defect; this one names a trend — erosion here is not one bad exemption but MANY
        // reasonable ones, each arguable on its own, until no member is refused outright and
        // every other assertion is still green. Floored directly, so the thing that would be lost
        // is the thing measured. It must not run before `offences`: one member granting a second
        // exemption would then stop the run here, and a SECOND member's leak-shaped offence would
        // never be printed. A trend may never mask a defect.
        Population.of("members with no request-scoped exemption", containedMembers().stream()
                        .filter(member -> member.requestScopedCallers().isEmpty())
                        .map(Contained::key).toList())
                .floor(UNEXEMPTED_MEMBER_FLOOR, WHY_THE_POPULATION_MATTERS, List.of(
                        "an exemption was granted — if it is right, it is still a narrowing of"
                        + " this arm: lower the floor in the SAME commit and name the member;",
                        "a member left the population — check MEMBER_FLOOR first, which measures"
                        + " that directly and should have gone red before this did."));
    }

    /**
     * <strong>And every declared caller really calls it</strong> — which is what stops the arm
     * above being vacuous.
     *
     * <p>Every assertion here is of the form "nothing offends", so a walk that stopped finding call
     * sites — a stale {@code target/classes}, a renamed owner, an import option that dropped the
     * tree — is the greenest possible answer. This asks the opposite question of the same walk: the
     * names the member declares must be names the walk actually produced. It also refuses a
     * declaration that outlived its subject, which is the {@code Population.excluding} rule applied
     * to an allow-list: a name left behind by a rename is a written decision about a class that is
     * no longer there, and the next class to take that name inherits a reason nobody wrote about it.
     *
     * <p>A member declaring {@code callers = {}} is exempt from this arm and only from this arm: it
     * claims no production class reaches it at all, so zero call sites is the pass, and the arm
     * above is what holds it.
     */
    @Test
    void everyDeclaredCallerIsStillACallerSoTheWalkCannotBeSilentlyEmpty() {
        var stale = new LinkedHashMap<String, List<String>>();
        for (var member : containedMembers()) {
            if (member.callers().isEmpty()) {
                continue;
            }
            var live = callSitesOf(member).stream().map(CalledOnlyFromSealTest::ownerOf)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            var dead = member.callers().stream()
                    .filter(declared -> live.stream().noneMatch(
                            caller -> admits(List.of(declared), caller)))
                    .toList();
            if (!dead.isEmpty()) {
                stale.put(member.key(), dead);
            }
        }

        assertThat(stale)
                .withFailMessage("""

                        A @CalledOnlyFrom declares a caller that does not call it: %s

                        Either the walk has stopped working — a stale target/classes (`mvnw clean \
                        compile`), a renamed owner, an import that no longer reaches the tree — in \
                        which case the sibling arm is passing over nothing and is worth nothing; or \
                        the caller was renamed, moved or deleted and the declaration outlived it. \
                        Fix the declaration in the same commit as the rename: an allow-list entry \
                        whose subject is gone is a written decision the next class to take that \
                        name inherits without anybody having written it about them.

                        A member that genuinely has no production caller declares `callers = {}` \
                        and is not asked this question.""", stale)
                .isEmpty();
    }

    /**
     * <strong>The unit control: no marked name is overloaded.</strong>
     *
     * <p>{@code ProductionBytecode.callSitesOfMethods} matches a call by its target owner and name
     * and never by its signature — deliberately, so that a fold's overloads stay one act — which
     * means this seal cannot tell two same-named methods apart. Marked one of a pair and the arms
     * above would answer about both: the callers of the <em>other</em> overload would read as
     * undeclared callers of this one (a false red), and a caller that moved from the marked
     * overload to its twin would read as compliant (a false green). Neither is a refusal anybody
     * could act on, so the overload is refused outright instead.
     *
     * <p>The second assertion is the granularity control that goes with it. The population is keyed
     * on {@code type#method} for a method and on {@code type} for a type, and a future change that
     * keyed it on the class alone would merge two marked methods of one interface into one member —
     * the shape where a population and its check stop being the same unit and the seal reports
     * coverage it does not have. Keys are therefore asserted distinct, and at least one is asserted
     * to carry its element name.
     */
    @Test
    void noContainedNameIsOverloadedAndNoTwoMembersShareAKey() {
        var members = containedMembers();
        var overloaded = new ArrayList<String>();
        for (var member : members) {
            for (var name : member.names()) {
                var sameName = offeredMethodsOf(member.owner())
                        .filter(m -> m.getName().equals(name)).count();
                if (sameName > 1) {
                    overloaded.add(member.key() + " → " + name + " (" + sameName + " overloads)");
                }
            }
        }

        assertThat(overloaded)
                .withFailMessage("""

                        @CalledOnlyFrom is on an overloaded name: %s

                        The walk under this seal matches a call by owner and NAME, never by \
                        signature, so it cannot tell the overloads apart: it would report the other \
                        overload's callers as undeclared callers of this one, and would stay green \
                        when a caller moved between them. Give this member a seal that matches on \
                        the parameter list — MailAddressesThrottleKeyTest is the worked example — \
                        and name that seal in the member's javadoc instead of marking it here.""",
                        overloaded)
                .isEmpty();

        var keys = members.stream().map(Contained::key).toList();
        assertThat(new LinkedHashSet<>(keys))
                .withFailMessage("two marked members share the population key %s — the key must be "
                                 + "type#method for a method and type for a type, or two marked "
                                 + "methods of one interface collapse into one member and the arms "
                                 + "above cover one of them while reading as covering both", keys)
                .hasSize(keys.size());
        assertThat(keys)
                .withFailMessage("no member is keyed on its element (%s): the population has been "
                                 + "narrowed to types, so a method-level marker is now invisible "
                                 + "to it", keys)
                .anyMatch(key -> key.contains("#"));
    }

    // ------------------------------------------------------------------ the tripwire

    /**
     * <strong>A caller-set claim in production prose names the test that holds it.</strong>
     *
     * <p>This is how a member <em>joins</em> the population. The three arms above are about members
     * somebody has already marked; this one is about the next unscoped finder whose javadoc says
     * "the only caller is …" and whose containment is, once again, a sentence. It refuses the claim
     * that names nothing — the author either marks the member (and names this seal) or names the
     * bespoke seal that holds it, and both are a deliberate line in the same commit.
     *
     * <p><strong>This is the secondary accusation, and its blind spots are named rather than
     * implied.</strong> (a) It reads prose, so a rewording takes a claim out of its population —
     * chosen direction: what is lost is an accusation, never a check, because the member's own
     * guard is the marker, which no rewording can touch. (b) Its shapes are a <em>sample</em> of
     * how this codebase writes the claim, and were measurably incomplete: HD-340's review added
     * five members, whose claims read "Seeding only", "exist for the operator metrics", "the only
     * load behind RolePermissionCache" and "every caller is visible in one grep" — and this set,
     * as first written, matched <strong>none</strong> of them. Two of those phrasings are shapes
     * now; "exist(s) for the …" is deliberately NOT, because the same construction carries
     * no claim at all a few lines away in the same file ({@code UserRepository}: "It exists for the
     * row a future writer leaves"), and a shape that accuses on sight of a common English phrase
     * teaches a reader to add a pointer to silence it. (c) It resolves a pointer by checking the
     * named test exists and mentions either the claiming type or {@code CalledOnlyFrom} — not that
     * the test asks anything about this member.
     *
     * <p><strong>Why it is not a code-derived population, which was the obvious ask.</strong>
     * Measured 2026-10-23 over {@code Doors.tables()} × {@code Doors.repositoryFinders}: of the 41
     * entities, 26 map a workspace-scoped table, and their repositories offer <strong>62 declared
     * and 651 inherited</strong> finders carrying no workspace or project term. A rule demanding a
     * marker or a named seal from each of those 713 would be a rule nobody can satisfy, and the
     * first thing it would teach is how to silence it. The population rule stays the marker; this
     * arm stays an accusation.
     */
    @Test
    void everyCallerSetClaimInProductionNamesALiveTest() throws IOException {
        var suite = suiteTypes();
        var units = SourceCallSites.unitsUnder(SourceCallSites.MAIN_SOURCES, Set.of());
        assertThat(units.size())
                .as("production compilation units read — under this the walk is not looking at the "
                    + "tree and this arm judges nothing")
                .isGreaterThanOrEqualTo(MAIN_UNIT_FLOOR);

        var blocks = 0;
        var claims = new ArrayList<SourceCallSites.CommentBlock>();
        var unheld = new ArrayList<String>();
        for (var unit : units) {
            for (var block : SourceCallSites.commentBlocks(unit)) {
                blocks++;
                var text = block.text().toLowerCase(Locale.ROOT);
                if (CLAIM_SHAPES.stream().noneMatch(shape -> shape.matcher(text).find())) {
                    continue;
                }
                claims.add(block);
                if (!namesALiveTest(block, unit.type(), suite)) {
                    // The shape is part of the refusal: a reader who disagrees with the accusation
                    // needs to see WHICH phrase accused them, or the only available response is to
                    // add a pointer they do not believe in.
                    var fired = CLAIM_SHAPES.stream().filter(shape -> shape.matcher(text).find())
                            .map(Pattern::pattern).toList();
                    unheld.add(block.site() + " " + fired + " — " + excerpt(block.text()));
                }
            }
        }

        assertThat(blocks)
                .as("comment blocks read across the production tree — under this the block scanner "
                    + "has stopped seeing prose and every claim below is invisible")
                .isGreaterThanOrEqualTo(COMMENT_BLOCK_FLOOR);
        assertThat(claims.size())
                .as("caller-set claims found in production prose — under this the claim shapes no "
                    + "longer match how this codebase writes one, and the arm is vacuous")
                .isGreaterThanOrEqualTo(CLAIM_FLOOR);
        assertThat(unheld)
                .withFailMessage("""

                        A comment claims who may call a member and names nothing that holds it:
                          %s

                        A containment that lives in a sentence is held by whoever last read the \
                        sentence, and the member has no symptom when it stops being true — an \
                        unscoped read answers, an unstamped row inserts, a send sends. Two ways to \
                        close it, both one line. Either mark the member with \
                        @CalledOnlyFrom(callers, because, instead) and name CalledOnlyFromSealTest \
                        in its javadoc, after which the caller set is derived from bytecode and a \
                        new caller is a red build. Or, if the claim is about one overload of a name \
                        or about something a static call graph cannot see, write the seal that can \
                        — MailAddressesThrottleKeyTest is the worked example — and name it here.

                        Naming a test that does not exist is refused; so is naming one that \
                        mentions neither this type nor CalledOnlyFrom.""",
                        String.join("\n  ", unheld))
                .isEmpty();
    }

    // ------------------------------------------------------------------ population

    /**
     * Every marked member, from production bytecode, in key order. A type-level marker contributes
     * one member covering every method that type OFFERS — inherited included, see
     * {@link #offeredMethodsOf} — and a method-level marker contributes one per method. Both are
     * keyed on the element, which is the unit the rule is about.
     */
    private static Population<Contained> containedMembers() {
        var tree = ProductionBytecode.main();
        assertThat(tree.size())
                .as("classes imported from com.hamstrack (production bytecode) — under %d the "
                    + "importer is not looking at target/classes and every arm here is vacuous",
                        IMPORT_FLOOR)
                .isGreaterThanOrEqualTo(IMPORT_FLOOR);

        var members = new ArrayList<Contained>();
        for (JavaClass type : tree) {
            if (type.isAnnotatedWith(CalledOnlyFrom.class)) {
                var owner = type.reflect();
                members.add(member(type.getName(), owner,
                        offeredMethodsOf(owner).map(Method::getName)
                                .collect(Collectors.toCollection(LinkedHashSet::new)),
                        type.getAnnotationOfType(CalledOnlyFrom.class)));
            }
            type.getMethods().stream()
                    .filter(method -> method.isAnnotatedWith(CalledOnlyFrom.class))
                    .forEach(method -> members.add(member(
                            type.getName() + "#" + method.getName(), type.reflect(),
                            Set.of(method.getName()),
                            method.getAnnotationOfType(CalledOnlyFrom.class))));
        }
        members.sort(Comparator.comparing(Contained::key));
        return Population.of("members whose containment is a declared caller set", members)
                .floor(MEMBER_FLOOR, WHY_THE_POPULATION_MATTERS, POPULATION_CAUSES);
    }

    private static Contained member(String key, Class<?> owner, Set<String> names,
                                    CalledOnlyFrom declaration) {
        return new Contained(key, owner, names, List.of(declaration.callers()),
                declaration.because(), declaration.instead(),
                List.of(declaration.requestScopedCallers()));
    }

    /**
     * <strong>Every production class a web bean dispatches to, plus the web beans themselves.</strong>
     * One hop, by measurement: {@link #REQUEST_DISPATCHED_CEILING} carries the figures for this
     * relation and for the transitive one it deliberately does not use.
     *
     * <p>Carries its own <strong>ceiling</strong>, which is the unusual half. Every other scan here
     * is floored against collapsing to nothing; this one is a discriminator, so its failure mode is
     * the opposite — a predicate that grew to cover the tree would admit nothing, refuse nothing and
     * pass every floor ever written over it. The ceiling is a fraction of the production classes so
     * it cannot be satisfied by the tree merely getting bigger.
     */
    private static Set<String> requestDispatchedClasses() {
        var webBeans = Doors.webBeans().floor(WEB_BEAN_FLOOR);
        var roots = webBeans.stream().map(Class::getName).collect(Collectors.toSet());
        var dispatched = new LinkedHashSet<>(roots);
        for (JavaClass clazz : ProductionBytecode.main()) {
            if (!roots.contains(clazz.getName())) {
                continue;
            }
            clazz.getMethodCallsFromSelf()
                    .forEach(call -> dispatched.add(call.getTargetOwner().getName()));
            clazz.getMethodReferencesFromSelf()
                    .forEach(ref -> dispatched.add(ref.getTargetOwner().getName()));
        }
        var ours = dispatched.stream().filter(name -> name.startsWith("com.hamstrack."))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        var production = Doors.productionClasses().size();
        assertThat((double) ours.size() / production)
                .as("classes a web bean dispatches to: %d of %d production classes. Above this "
                    + "ceiling the predicate behind arm (2) has stopped discriminating — it would "
                    + "refuse every declared caller, every member would need an entry in "
                    + "requestScopedCallers, and the arm would be a formality. The figure above "
                    + "is this run's; the ceiling's javadoc carries the measurement it was set "
                    + "from and the relation that produced it. Find out what widened it (a "
                    + "controller that now calls a hub class, a root that is no longer a web "
                    + "bean); do not raise the ceiling to make a run pass",
                        ours.size(), production)
                .isLessThan(REQUEST_DISPATCHED_CEILING);
        return ours;
    }

    /**
     * <strong>Every method the type OFFERS — declared and inherited</strong> — bridges and other
     * compiler-generated members dropped.
     *
     * <p>Declared-only was wrong in both of the places it was used, and in the same direction.
     * A type-level marker would have covered only the methods the interface typed out, on a type
     * whose own javadoc reasons about what {@code extends JpaRepository} would hand it
     * ({@code save}, {@code saveAll}, {@code deleteAll}) — so the claim "a method added to a marked
     * interface is covered on the day it is added" was true for declared methods and false for the
     * twenty that arrive by inheritance, which are exactly the ones nobody types and nobody
     * reviews. And the overload control counted only declared overloads, so a name overloaded
     * <em>across</em> the inheritance boundary evaded the refusal and the name-keyed walk then
     * answered about a method nobody marked.
     *
     * <p>Union rather than {@code getMethods()} alone: that one returns public members only, and a
     * marker on a package-private method is legal and must still be counted. A bridge carries the
     * name of the method it bridges, so counting it would report an overload nobody wrote.
     */
    private static Stream<Method> offeredMethodsOf(Class<?> owner) {
        var offered = new LinkedHashSet<Method>();
        offered.addAll(Arrays.asList(owner.getMethods()));
        offered.addAll(Arrays.asList(owner.getDeclaredMethods()));
        return offered.stream()
                .filter(method -> method.getDeclaringClass() != Object.class)
                .filter(method -> !method.isSynthetic() && !method.isBridge());
    }

    private static List<String> callSitesOf(Contained member) {
        return ProductionBytecode.callSitesOfMethods(Set.of(), member.owner(), member.names());
    }

    private static String ownerOf(String site) {
        return site.substring(0, site.indexOf('#'));
    }

    /**
     * Whether a call site runs while its class is being built rather than when a method of it is
     * called. This is the fact every {@code requestScopedCallers} exemption in this tree actually
     * rests on, so it is asserted instead of assumed.
     *
     * <p><strong>A lambda written in a constructor needs no special case here, and that is
     * measured rather than assumed</strong> (2026-10-24). The obvious worry is that
     * {@code Gauge.builder(…, r -> repo.totalBytesUsed())} compiles to a synthetic
     * {@code lambda$new$0} and would be refused — with a message telling its author to move the
     * call into the constructor, where they had already put it. It does not happen on this walk:
     * ArchUnit attributes an access inside a lambda to the <em>enclosing</em> code unit, so that
     * exact plant reported one site, {@code ProductMetrics#<init>}, indistinguishable from the
     * method-reference form in use today. Clauses for {@code lambda$new$…} were written, measured
     * to be unreachable, and removed: a condition that cannot fire is a claim about the walk that
     * is not true of it.
     *
     * <p>What would make it true again: a different ArchUnit version that reports synthetic
     * origins, or moving this question onto a source parser. The failure direction is a loud false
     * refusal of a legitimate constructor lambda, never a silent admission — so this is a note for
     * whoever sees that refusal, not a guard to pre-build.
     */
    private static boolean isConstructionTime(String site) {
        var method = site.substring(site.indexOf('#') + 1);
        return method.equals("<init>") || method.equals("<clinit>");
    }

    /** A declared caller admits itself and its nested classes, and nothing else. */
    private static boolean admits(List<String> declared, String caller) {
        return declared.stream()
                .anyMatch(name -> caller.equals(name) || caller.startsWith(name + "$"));
    }

    /** {@code SimpleName -> source text} for every unit under {@code src/test/java}. */
    private static Map<String, String> suiteTypes() throws IOException {
        var types = new LinkedHashMap<String, String>();
        for (var unit : SourceCallSites.unitsUnder(SourceCallSites.TEST_SOURCES, Set.of())) {
            types.put(unit.type(), String.join("\n", unit.lines()));
        }
        assertThat(types.size())
                .as("test compilation units read — under this the pointer resolution would refuse "
                    + "every live test and this arm would be a false red everywhere")
                .isGreaterThanOrEqualTo(TEST_UNIT_FLOOR);
        return types;
    }

    private static boolean namesALiveTest(SourceCallSites.CommentBlock block, String claimingType,
                                          Map<String, String> suite) {
        var pointers = NAMES_A_TEST.matcher(block.text());
        while (pointers.find()) {
            var source = suite.get(pointers.group(1));
            if (source != null
                && (source.contains(claimingType) || source.contains("CalledOnlyFrom"))) {
                return true;
            }
        }
        return false;
    }

    /** The claim, short enough that a dozen of them still fit inside the message budget. */
    private static String excerpt(String text) {
        return text.length() <= 120 ? text : text.substring(0, 117) + "…";
    }

    /**
     * The refusal, and it is the whole point of the arm: whoever trips it is holding a compiler
     * that just accepted the call, so what they need is why not and what to do instead — both
     * authored on the member rather than generalised here, because the three members' reasons are
     * three different reasons. Bounded at three offenders, which is what keeps the message inside
     * the 25-line budget at five lines each; the fourth is a count, since a number a message
     * computes cannot go stale the way a number somebody typed does.
     */
    private static String refusal(Map<Contained, List<String>> offences) {
        var message = new StringBuilder("\n\nA production class reaches a member that declares who "
                                        + "may reach it:\n\n");
        var shown = 0;
        for (var offence : offences.entrySet()) {
            if (shown++ == 3) {
                message.append("  …and ").append(offences.size() - 3).append(" more member(s).\n");
                break;
            }
            var member = offence.getKey();
            message.append("  ").append(member.key()).append('\n')
                    .append("    called from : ").append(String.join(", ", offence.getValue()))
                    .append('\n')
                    .append("    declared    : ").append(member.callers()).append('\n')
                    .append("    because     : ").append(member.because()).append('\n')
                    .append("    instead     : ").append(member.instead()).append('\n');
        }
        return message.append("""

                If the new caller is legitimate, add its fully-qualified class name to that \
                member's @CalledOnlyFrom in the same commit — the declaration IS the seal, and \
                editing it is how a caller set changes. If it is not, take the path named under \
                "instead"; it is the one the member was built to be reached by.""").toString();
    }
}
