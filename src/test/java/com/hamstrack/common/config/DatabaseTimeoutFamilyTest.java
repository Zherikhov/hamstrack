package com.hamstrack.common.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * <strong>The three database bounds are one family, and this file is the derivation</strong>
 * (HD-233, ADR-0034). Its failure messages are the argument; if one of them fires, read it rather
 * than the number it complains about.
 *
 * <blockquote>
 * The <strong>lock</strong> bound is how long a transaction may wait for a <strong>row</strong>.
 * The <strong>statement</strong> bound is how long one statement may <strong>run</strong>, and must
 * be at least twice the lock bound because it counts that wait. The <strong>acquisition</strong>
 * bound is how long a request may wait for a <strong>connection</strong>, and must be at least the
 * lock bound, because a connection held by a lock-waiting transaction is legitimately unavailable
 * for exactly that long. None of the three is derived from the size of the pool, and the
 * acquisition bound is not derived from the statement bound in either direction.
 * </blockquote>
 *
 * <p>Two of the three did not move when the third was chosen, and that is the part most likely to
 * be "tidied" later: the acquisition bound is a <em>queueing</em> budget and can be short while a
 * statement legitimately runs for ten seconds. The family the ticket originally suggested — lock
 * 500 / statement 1000 / acquisition 2000 — is expressible and was rejected on evidence: 1000 is
 * {@code statement-timeout-ms}'s own {@code @Min} floor and sits below the measured 3.92 s median
 * expensive read, on write paths with nothing the caller can narrow.
 *
 * <p>Mostly plain unit tests over {@link DatabaseTimeoutConsistency}, following
 * {@link PoolShareConsistencyTest}: the behaviour is arithmetic over three property sources and one
 * environment key, {@code @PostConstruct} is what runs it, and a Spring context per
 * misconfiguration would buy nothing but slower tests. {@link StatementTimeoutPropertiesTest}
 * covers the context-level wiring, where the same throw stops a boot.
 */
class DatabaseTimeoutFamilyTest {

    private static final int SHIPPED_LOCK_MS = 3000;
    private static final int SHIPPED_STATEMENT_MS = 10_000;
    private static final long SHIPPED_ACQUISITION_MS = 3000;

    // ------------------------------------------------------------------ the shipped values

    /**
     * The three numbers this release ships, read out of the file an operator's environment
     * overrides. They are quoted in {@code .env.prod.example}, {@code docs/self-hosting.md}, both
     * API references and ADR-0034; a change here is a change to all of them.
     */
    @Test
    void theShippedFamilyIsThreeThousandTenThousandThreeThousand() throws IOException {
        var properties = applicationProperties();

        assertThat(propertyDefault(properties, "app.locking.lock-timeout-ms"))
                .isEqualTo(SHIPPED_LOCK_MS);
        assertThat(propertyDefault(properties, "app.persistence.statement-timeout-ms"))
                .isEqualTo(SHIPPED_STATEMENT_MS);
        assertThat(propertyDefault(properties, "spring.datasource.hikari.connection-timeout"))
                .as("the acquisition bound is CHOSEN — leaving this line out returns the "
                    + "application to Hikari's unset 30 s, which is the state HD-233 exists to "
                    + "delete and which five configuration classes once quoted as a fact")
                .isEqualTo(SHIPPED_ACQUISITION_MS);
    }

    /**
     * <strong>{@code validationTimeout} is the same number spelled twice, not a fourth
     * decision.</strong> Verified against {@code HikariConfig}: it defaults to 5000 ms and is
     * related to {@code connectionTimeout} in neither direction, while a borrowed connection idle
     * past {@code aliveBypassWindowMs} is aliveness-checked using it. Left at its default, one
     * {@code getConnection()} can cost the acquisition bound PLUS five seconds — in precisely the
     * case (a degraded database) the bound exists for.
     */
    @Test
    void bothHikariLinesReadTheOneVariable() throws IOException {
        var properties = applicationProperties();

        assertThat(properties)
                .contains("spring.datasource.hikari.connection-timeout="
                          + "${DB_CONNECTION_TIMEOUT_MS:3000}")
                .as("if these two ever diverge, the bound overruns by the difference exactly when "
                    + "it matters")
                .contains("spring.datasource.hikari.validation-timeout="
                          + "${DB_CONNECTION_TIMEOUT_MS:3000}");
    }

    /**
     * <strong>Identical in {@code dc} and {@code cloud}</strong>, resolved through the real
     * configuration rather than by reading the two profile files — an assertion that greps those
     * files passes just as happily when the value has moved into a third place.
     *
     * <p>The reason there is no divergence: the environment variable already is the per-deployment
     * knob, so a profile default would be a second, invisible one. Cloud has more replicas and more
     * tenants, so acquisition contention is likelier there; DC is likelier to run one large tenant
     * on small hardware, so the cost of shedding is higher there. They point in opposite directions
     * and cancel.
     */
    @Test
    void dcAndCloudResolveTheSameThreeBounds() {
        var dc = configuration("dc");
        var cloud = configuration("cloud");

        for (var key : List.of("app.locking.lock-timeout-ms",
                               "app.persistence.statement-timeout-ms",
                               "spring.datasource.hikari.connection-timeout",
                               "spring.datasource.hikari.validation-timeout")) {
            assertThat(cloud.getProperty(key))
                    .as("%s must resolve identically in both modes — a resource bound that differs "
                        + "by profile is a second knob nobody can see in the .env", key)
                    .isEqualTo(dc.getProperty(key));
        }
    }

    // ------------------------------------------------------------------ silence at the defaults

    /**
     * <strong>A default boot logs no WARN from this check.</strong> Asserted as an absence on
     * purpose: a check that "passes" because a rule was routed around looks exactly like one whose
     * rules are true, and the deleted {@code POOL_TURNOVER_SHARE} rule fired on every boot at the
     * shipped defaults — which is how a startup log stops being read.
     */
    @Test
    void theShippedDefaultsPassWithoutAWarning() {
        assertThat(warningsFrom(() -> check(SHIPPED_STATEMENT_MS, SHIPPED_LOCK_MS,
                                            String.valueOf(SHIPPED_ACQUISITION_MS))))
                .as("3000 / 10000 / 3000 against a 30 s stop grace satisfies both soft rules: the "
                    + "acquisition bound is not below the lock bound, and the statement bound is "
                    + "not above the grace")
                .isEmpty();
    }

    // ------------------------------------------------------------------ H-1

    /** Unchanged by HD-233, and re-pinned here because the family now lives in one file. */
    @Test
    void aStatementBoundInsideTwiceTheLockBoundRefusesTheBoot() {
        assertThatThrownBy(() -> check(5999, 3000, "3000"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.persistence.statement-timeout-ms")
                .hasMessageContaining("DB_STATEMENT_TIMEOUT_MS")
                .hasMessageContaining("409")
                .hasMessageContaining("422");
    }

    // ------------------------------------------------------------------ H-2

    /**
     * <strong>The zero trap, and it is the reason this rule exists at all.</strong> Verified against
     * {@code HikariConfig.setConnectionTimeout}: {@code 0} is mapped to {@code Integer.MAX_VALUE} —
     * about 24.8 days — and accepted in silence, while anything below 250 ms throws. So this is the
     * one member of the family for which the pool will not refuse the plausible-looking zero on our
     * behalf, and {@code @Min} cannot be used because the value belongs to Hikari's own record.
     */
    @Test
    void zeroRefusesTheBootAndSaysItMeansNoBound() {
        assertThatThrownBy(() -> check(SHIPPED_STATEMENT_MS, SHIPPED_LOCK_MS, "0"))
                .as("""
                    A ZERO ACQUISITION BOUND MUST NOT BOOT.

                    Hikari reads it as Integer.MAX_VALUE, so every request that finds the pool \
                    full parks for ~24.8 days holding a Tomcat worker - which is the unbounded \
                    wait this setting exists to delete, restored by a value that looks like \
                    "off". The message must say NO BOUND rather than "no wait", because an \
                    operator who typed 0 meant the second one.""")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_CONNECTION_TIMEOUT_MS")
                .hasMessageContaining("NO BOUND");
    }

    /**
     * The blank arrives the way it arrives in production: {@code DB_CONNECTION_TIMEOUT_MS=} is how
     * an operator ordinarily disables a line in a {@code .env}, and it binds an <em>empty</em>
     * value rather than an absent one — so the {@code :3000} placeholder default never applies.
     * Same rule, and the same refusal, as its two siblings; the mechanism differs only in that
     * theirs is a primitive {@code int} and this one is read as text.
     */
    @Test
    void aBlankValueRefusesTheBootRatherThanMeaningTheDefault() {
        assertThatThrownBy(() -> check(SHIPPED_STATEMENT_MS, SHIPPED_LOCK_MS, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BLANK")
                .hasMessageContaining("DB_CONNECTION_TIMEOUT_MS");
    }

    @Test
    void aValueThatIsNotANumberRefusesTheBoot() {
        assertThatThrownBy(() -> check(SHIPPED_STATEMENT_MS, SHIPPED_LOCK_MS, "3s"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_CONNECTION_TIMEOUT_MS");
    }

    /**
     * Below Hikari's floor the pool throws too — with a message that names neither the property nor
     * the variable an operator typed. Refusing first is what makes the failure actionable.
     */
    @Test
    void belowHikarisOwnFloorTheRefusalNamesTheVariableAnOperatorTyped() {
        assertThatThrownBy(() -> check(SHIPPED_STATEMENT_MS, SHIPPED_LOCK_MS, "100"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_CONNECTION_TIMEOUT_MS")
                .hasMessageContaining("250");
    }

    /**
     * Absent is the one legitimate case and it is not "unset by an operator": the shipped
     * {@code application.properties} sets this key, so absence means that line was deleted. The
     * fallback is what would then be in force — and it is Hikari's default rather than ours, which
     * is the distinction the constant's name carries.
     *
     * <p>The stop grace is widened here for the reason the test below states: at the shipped mail
     * defaults that fallback does not fit the shutdown at all.
     */
    @Test
    void anAbsentKeyFallsBackToHikarisOwnDefault() {
        var check = new DatabaseTimeoutConsistency(
                new StatementTimeoutProperties(SHIPPED_STATEMENT_MS),
                new LockingProperties(SHIPPED_LOCK_MS),
                mail(15, 100, 60),
                new MockEnvironment());

        assertThatCode(check::check).doesNotThrowAnyException();
        assertThat(check.acquisitionBoundMs())
                .isEqualTo(DatabaseTimeoutConsistency.HIKARI_DEFAULT_CONNECTION_TIMEOUT_MS);
    }

    /**
     * <strong>H-3 puts a ceiling on the acquisition bound that no {@code @Max} states, and this is
     * the one consequence of HD-233 an operator can meet by accident.</strong> At the shipped mail
     * defaults the shutdown has 30 000 − 15 000 drain − 1000 commit − 100 rows = <strong>13 900
     * ms</strong> left for a connection acquisition, so anything above that refuses the boot — most
     * plausibly the operator who reads the release note, decides they would rather wait than shed,
     * and reaches for the old 30 s.
     *
     * <p>Refusing is right and the arithmetic says why: a 30 s acquisition inside a 30 s grace
     * genuinely cannot complete, so the alternative is a shutdown that silently loses the queue.
     * What makes it acceptable is that the message names four remedies, one of which
     * ({@code APP_STOP_GRACE_SECONDS}) is the one that actually buys the wait they asked for.
     * {@code .env.prod.example} and {@code docs/self-hosting.md} carry this ceiling in prose,
     * because a bound an operator can reach by following a release note has to be findable before
     * the boot fails rather than after.
     */
    @Test
    void theOldThirtySecondWaitNoLongerFitsTheShippedShutdownAndSaysSo() {
        assertThatThrownBy(() -> check(SHIPPED_STATEMENT_MS, SHIPPED_LOCK_MS, "30000"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("acquisition 30000 ms")
                .hasMessageContaining("APP_STOP_GRACE_SECONDS");

        assertThatCode(() -> check(SHIPPED_STATEMENT_MS, SHIPPED_LOCK_MS, "13900"))
                .as("and the ceiling is exactly where the arithmetic puts it, so the number in the "
                    + "operator documentation is checked rather than remembered")
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> check(SHIPPED_STATEMENT_MS, SHIPPED_LOCK_MS, "13901"))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * <strong>The same ceiling seen from the other end: the drain an operator may set.</strong>
     * The acquisition is one term of the shutdown, so bounding it bounds every other term too —
     * and the operator documentation quotes the drain's practical ceiling as a number, which is a
     * number that goes stale the moment a term is added. It did: the passages describing this
     * arithmetic said <strong>28 s</strong>, which was the ceiling of
     * {@link MailAsyncProperties.Async#isShutdownWithinTheStopGrace()} alone (28 000 + 1000 + 100
     * against 30 000) and stopped being the operative one when the acquisition joined the sum. At
     * the shipped defaults the drain has 30 000 − 3000 acquisition − 1000 commit − 100 rows =
     * <strong>25 900 ms</strong>, i.e. <strong>25 s</strong> — so an operator who followed the old
     * text and set 28 got a boot refusal from the document that told them 28 was fine.
     *
     * <p>Asserted rather than described for the reason the 13 900 ceiling above is: this is
     * arithmetic the application computes at every boot, so a document quoting a different number
     * is a defect a test can hold rather than a review round.
     */
    @Test
    void theDrainCeilingAtTheShippedAcquisitionIsTwentyFiveSeconds() {
        assertThatCode(() -> checkWithDrain(25))
                .as("the number .env.prod.example and docs/self-hosting.md quote as the practical "
                    + "ceiling for MAIL_ASYNC_SHUTDOWN_DRAIN_SECONDS at the shipped defaults")
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> checkWithDrain(26))
                .as("and 26 is the first value that does not boot, so the quoted ceiling is the "
                    + "real one and not a rounded one")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_STOP_GRACE_SECONDS");

        assertThat(new MailAsyncProperties.Async(2, 5, 100, 28, 30).isShutdownWithinTheStopGrace())
                .as("28 is what the nested record's own check still accepts — it cannot see the "
                    + "acquisition — which is exactly how the stale 28 in the operator "
                    + "documentation stayed plausible")
                .isTrue();
        assertThatThrownBy(() -> checkWithDrain(28))
                .isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------------ H-2, by NAME

    /**
     * <strong>The zero trap is also reachable by a name, and this is the spelling Boot's own
     * documentation prints.</strong>
     *
     * <p>{@code Environment.getProperty} resolves an environment variable through
     * {@code SystemEnvironmentPropertySource.checkPropertyName}, which tries dots→{@code _} and
     * hyphens→{@code _}. Boot's configuration binder — the thing that actually puts this value into
     * {@code HikariConfig} — additionally accepts the dashes-<em>removed</em> form. So
     * {@code SPRING_DATASOURCE_HIKARI_CONNECTIONTIMEOUT=0} binds a zero into the pool while a check
     * reading the {@code Environment} sees the {@code 3000} from {@code application.properties},
     * passes every rule, and then reports a bound that is not in force — in the WARN an operator is
     * meant to trust, and as the acquisition term of H-3's shutdown arithmetic.
     *
     * <p>The first assertion pins the <em>reason</em> rather than the fix: it is the gap itself, and
     * if a future Spring closes it this test should be read again rather than deleted.
     */
    @Test
    void theAcquisitionBoundIsReadWithTheRELAXEDMatchingThePoolItselfBindsWith() {
        var environment = environmentWithVariable("SPRING_DATASOURCE_HIKARI_CONNECTIONTIMEOUT", "0");

        assertThat(environment.getProperty(DatabaseTimeoutConsistency.ACQUISITION_PROPERTY))
                .as("the gap this test exists for: a plain Environment lookup does not see the "
                    + "dashes-removed spelling, and Hikari's own binding does")
                .isNull();
        assertThatThrownBy(() -> new DatabaseTimeoutConsistency(
                new StatementTimeoutProperties(SHIPPED_STATEMENT_MS),
                new LockingProperties(SHIPPED_LOCK_MS),
                mail(15, 100, 60),
                environment).check())
                .as("""
                    A ZERO THAT ARRIVES BY A LEGAL NAME MUST BE REFUSED LIKE ANY OTHER ZERO.

                    Read through Environment.getProperty this configuration passes every rule and \
                    then reports 3000 ms as the bound in force, while the pool holds \
                    Integer.MAX_VALUE - about 24.8 days. A guard that can be walked past by \
                    spelling the same key differently is a guard about a spelling.""")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NO BOUND");
    }

    /** And the same spelling in the direction that must be accepted, not merely not-refused. */
    @Test
    void aLegitimateValueInThatSameSpellingIsTheBoundInForce() {
        var check = new DatabaseTimeoutConsistency(
                new StatementTimeoutProperties(SHIPPED_STATEMENT_MS),
                new LockingProperties(SHIPPED_LOCK_MS),
                mail(15, 100, 30),
                environmentWithVariable("SPRING_DATASOURCE_HIKARI_CONNECTIONTIMEOUT", "5000"));

        check.check();

        assertThat(check.acquisitionBoundMs())
                .as("read through the Environment this would be Hikari's 30 s default, and every "
                    + "number the product then quotes would be wrong by 25 seconds")
                .isEqualTo(5000);
    }

    // ------------------------------------------------------------------ H-2, sealed against the pool

    /**
     * <strong>The seal: the bound the POOL holds is the one that was checked, or the boot
     * stops.</strong>
     *
     * <p>Every rule above reads a property; this one reads the built {@code HikariDataSource}. That
     * makes "the value the pool actually holds" the single source of truth the refusals and the WARN
     * quote, rather than a property they assert and do not verify — and it is immune to the two
     * things the rules cannot cover: a spelling this file has not enumerated, and an ordering
     * regression in which the pool is built before the check ever runs.
     */
    @Test
    void aPoolHoldingABoundNobodyCheckedRefusesTheBoot() {
        var check = checked(String.valueOf(SHIPPED_ACQUISITION_MS));

        assertThatThrownBy(() -> seal(check, pool(9000)).verify())
                .as("""
                    THE POOL MUST HOLD THE BOUND THAT WAS VALIDATED.

                    If it does not, every rule checked at boot was checked against a number that \
                    is not in force - including the ceiling that keeps the mail shutdown inside \
                    APP_STOP_GRACE_SECONDS - and the WARN beside every 503 DATABASE_BUSY quotes an \
                    operator a bound their instance is not using.""")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("9000")
                .hasMessageContaining("SPRING_DATASOURCE_HIKARI_CONNECTIONTIMEOUT");
    }

    /**
     * The zero is named separately although the mismatch above would also catch it: it is the one
     * value that means <em>no bound at all</em>, and a message that prints two numbers that differ
     * sends the reader looking for the second number rather than for the zero they typed.
     */
    @Test
    void aPoolHoldingNoBoundAtAllRefusesTheBootAndSaysSo() {
        var check = checked(String.valueOf(SHIPPED_ACQUISITION_MS));
        var pool = new com.zaxxer.hikari.HikariDataSource();
        pool.setConnectionTimeout(0);

        assertThatThrownBy(() -> seal(check, pool).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NO BOUND")
                .hasMessageContaining("24.8 days");
    }

    @Test
    void aPoolHoldingTheCheckedBoundStarts() {
        var check = checked(String.valueOf(SHIPPED_ACQUISITION_MS));

        assertThatCode(() -> seal(check, pool(SHIPPED_ACQUISITION_MS)).verify())
                .doesNotThrowAnyException();
    }

    /**
     * <strong>The same seal, one property over, because the same NAME defeats it there and nothing
     * else in the process would ever notice.</strong> {@code validation-timeout} is not a fourth
     * bound — {@code application.properties} spells {@code DB_CONNECTION_TIMEOUT_MS} into both
     * Hikari lines, because a borrowed connection idle past {@code aliveBypassWindowMs} is
     * aliveness-checked with it. What equality buys is a <em>ceiling</em> and not the absence of an
     * addition, which is the correction this test's earlier wording needed: in
     * {@code HikariPool.getConnection(hardTimeout)} the {@code isConnectionDead(...)} probe runs
     * after {@code connectionBag.borrow(timeout)} has returned and the remaining budget is
     * re-derived only afterwards, so one probe overruns the hard timeout by up to
     * {@code validationTimeout} at any value it is set to. Held equal, the worst case is bounded at
     * twice the acquisition bound and this process knows the figure; left apart it is that bound
     * plus one nothing here reads back — and a {@code SPRING_DATASOURCE_HIKARI_VALIDATIONTIMEOUT}
     * would win over {@code application.properties} in silence: the identical name-defeats-a-value
     * hole the acquisition seal exists for, against a number nothing else quotes.
     */
    @Test
    void aPoolWhoseValidationBoundHasComeApartFromItRefusesTheBootToo() {
        var check = checked(String.valueOf(SHIPPED_ACQUISITION_MS));

        assertThatThrownBy(() -> seal(check, pool(SHIPPED_ACQUISITION_MS, 5000)).verify())
                .as("""
                    THE TWO HIKARI LINES ARE ONE NUMBER, AND THE POOL HAS TO BE HOLDING IT TWICE.

                    5000 is Hikari's own validationTimeout default, i.e. what an instance gets when \
                    the second line is overridden away or was never applied. Nothing else in this \
                    process reads that value, so without this rule the divergence is invisible: \
                    every acquisition can then cost the bound this file validated PLUS five \
                    seconds, on a degraded database, which is the one case the bound is for.""")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(DatabaseTimeoutConsistency.VALIDATION_PROPERTY)
                .hasMessageContaining("5000")
                .hasMessageContaining("SPRING_DATASOURCE_HIKARI_VALIDATIONTIMEOUT")
                // And the sentence for the operator who did it ON PURPOSE. Everything else in this
                // refusal diagnoses an accident and tells them to remove whatever else is writing
                // the value, which is performable and is the wrong reading for someone who set the
                // property deliberately: they are sent hunting a bug that is them, and are never
                // told the intent itself is unsupported.
                .hasMessageContaining("not a supported configuration");
    }

    /**
     * <strong>Absence is not silence, and the difference is which absence.</strong> No
     * {@code DataSource} at all is every property unit test in this file and is skipped without a
     * word. A {@code DataSource} that exists and is not Hikari's cannot be verified — this instance
     * would then be quoting a property rather than a fact — so it WARNs. It does not refuse,
     * because refusing would make an otherwise legitimate alternative pool unbootable over a check
     * about Hikari.
     */
    @Test
    void aPoolThatIsNotHikarisIsNotVerifiedAndDoesNotPassInSilence() {
        var check = checked(String.valueOf(SHIPPED_ACQUISITION_MS));

        assertThat(warningsFrom(() -> seal(check, new org.springframework.jdbc.datasource
                .SimpleDriverDataSource()).verify(), DatabaseTimeoutConsistency
                .ThePoolHoldsTheBoundThatWasChecked.class))
                .hasSize(1)
                .first(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("could not be checked")
                // The WARN's reader is the ONLY person who will ever be told either of these, so
                // both belong in it. The first because H-3 can refuse the boot outright from
                // @PostConstruct, using this same number, BEFORE this check runs — an operator can
                // therefore be hard-refused by arithmetic and only afterwards told one of its terms
                // was never verified. The second because it is not a caveat about a number at all:
                // both writers of 503 DATABASE_BUSY discriminate on java.sql's
                // SQLTransientConnectionException — the type Hikari raises from
                // createTimeoutException, which a different pool is free never to raise — so on a
                // pool that does not, the refusal silently reverts to the 500 this ticket exists to
                // delete and the counter its alert is built on stays at zero. MAY rather than IS:
                // the discriminator is a standard type, so another pool that raises it is covered,
                // and a WARN that overstates in the alarming direction is still wrong.
                .contains("APP_STOP_GRACE_SECONDS")
                .contains("503 DATABASE_BUSY MAY NOT BE ANSWERED AT ALL");

        assertThat(warningsFrom(() -> seal(check, null).verify(),
                DatabaseTimeoutConsistency.ThePoolHoldsTheBoundThatWasChecked.class))
                .as("a context with no pool is not a deployment with an unverified one")
                .isEmpty();
    }

    /**
     * <strong>The other {@code null} {@code getIfUnique()} returns, which used to leave through the
     * same silent {@code return} as "no pool at all".</strong> Two {@code DataSource} beans with no
     * primary is a deployment whose pool this seal never read — the unverified-bound condition
     * exactly, reached through a bean definition rather than through a type — while the branch it
     * was collapsed into is the one reserved for a context that has no pool to verify. That
     * inverted "absence is not silence" three paragraphs above it in the same class: an instance
     * running an acquisition bound nothing checked, saying nothing, on the branch whose whole
     * justification is that there was nothing to check.
     *
     * <p>Low reachability on purpose — this is not a shape the product ships — which is why it warns
     * rather than refuses and why the defect was the comment. A rule that fires rarely is still a
     * rule that has to say what it did.
     */
    @Test
    void twoPoolsWithNoPrimaryAreNotVerifiedEitherAndDoNotPassInSilence() {
        var check = checked(String.valueOf(SHIPPED_ACQUISITION_MS));

        assertThat(warningsFrom(
                () -> sealWithNoPrimary(check, pool(SHIPPED_ACQUISITION_MS),
                        pool(SHIPPED_ACQUISITION_MS)).verify(),
                DatabaseTimeoutConsistency.ThePoolHoldsTheBoundThatWasChecked.class))
                .as("""
                    NO SINGLE POOL TO ASK IS AN UNVERIFIED BOUND, NOT AN ABSENT ONE.

                    getIfUnique() answers null for zero candidates AND for several with no \
                    primary. The first is every property unit test and is skipped without a word; \
                    the second is a running deployment whose pool nothing compared against the \
                    number every refusal, every WARN and the boot-time shutdown ceiling were \
                    computed from. If this is empty, the two have been collapsed back together and \
                    the seal fails open in silence for the second one.""")
                .hasSize(1)
                .first(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("could not be checked")
                .contains("2 DataSource")
                .contains("none of them is primary");
    }

    // ------------------------------------------------------------------ H-3

    /**
     * <strong>The shutdown residue write must fit inside the stop grace, and the acquisition is one
     * of its terms.</strong> At the shipped defaults: 15 000 drain + 3000 acquisition + 1000 commit
     * + 100 rows = 19 100 against a grace of 30 000.
     */
    @Test
    void theShippedShutdownFitsInsideTheStopGraceWithRoom() {
        assertThatCode(() -> check(SHIPPED_STATEMENT_MS, SHIPPED_LOCK_MS,
                                   String.valueOf(SHIPPED_ACQUISITION_MS)))
                .doesNotThrowAnyException();
    }

    /**
     * The combination the nested record's own {@code @AssertTrue} accepts and this one must not: a
     * drain of 26 s with a queue of 100 fits a 30 s grace on its own (26 000 + 1000 + 100 = 27 100)
     * and does not once a 3 s acquisition is counted.
     */
    @Test
    void aShutdownThatOnlyFitsWithoutTheAcquisitionRefusesTheBoot() {
        var async = new MailAsyncProperties.Async(2, 5, 100, 26, 30);
        assertThat(async.isShutdownWithinTheStopGrace())
                .as("the binding-time triple must still PASS here, or this test is proving "
                    + "something else: the whole point is that the term it cannot see is the one "
                    + "that overflows the grace")
                .isTrue();

        assertThatThrownBy(() -> new DatabaseTimeoutConsistency(
                new StatementTimeoutProperties(SHIPPED_STATEMENT_MS),
                new LockingProperties(SHIPPED_LOCK_MS),
                new MailAsyncProperties(async, null, null),
                environmentWith(String.valueOf(SHIPPED_ACQUISITION_MS))).check())
                .as("""
                    A SHUTDOWN THAT DOES NOT FIT MUST NOT BOOT.

                    Shutdown waits the drain, THEN writes whatever is still queued to \
                    failed_email as one batch - and that write has to obtain a connection first. \
                    Past the grace the process is SIGKILLed part-way through, every queued \
                    account-critical email is lost exactly as it was before HD-207, and there is \
                    now a mechanism in the code that makes a reader believe otherwise.""")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("acquisition")
                .hasMessageContaining("DB_CONNECTION_TIMEOUT_MS")
                .hasMessageContaining("APP_STOP_GRACE_SECONDS");
    }

    /**
     * <strong>The ordering edge that makes H-2 reachable at all.</strong> Measured, not reasoned:
     * booting the application with {@code DB_CONNECTION_TIMEOUT_MS=0} <em>without</em> this edge
     * fails while binding {@code spring.datasource.hikari.validation-timeout} — "validationTimeout
     * cannot be less than 250ms", a property the operator never set, explaining a floor where the
     * actual problem is that {@code connection-timeout=0} means <em>no bound</em>. The pool is built
     * first because the {@code EntityManagerFactory} needs it, so without a {@code depends-on} the
     * refusal above is a message nobody can reach.
     *
     * <p>Delete the {@code BeanFactoryPostProcessor} and this test fails while every other test in
     * this file still passes, which is the point: they call {@code check()} directly and cannot see
     * who runs first.
     *
     * <p><strong>What it does not prove, and where that is proved instead.</strong> This is a
     * hand-built bean factory, so it establishes the edge's arithmetic and not that the real context
     * orders that way — and an edge found by {@code getBeanNamesForType(DataSource.class, …)} fails
     * open in silence if a deployment ever supplies the pool behind a {@code FactoryBean} or under a
     * less specific type. Neither hole is closed here; both are closed by
     * {@link DatabaseTimeoutConsistency.ThePoolHoldsTheBoundThatWasChecked}, which reads the built
     * pool after refresh and therefore needs no belief about ordering at all, and by
     * {@link PoolBoundSealBootTest}, which boots the application to show that seal stops a boot.
     */
    @Test
    void thePoolIsBuiltAfterTheBoundsAreChecked() {
        var beanFactory = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition("dataSource",
                new org.springframework.beans.factory.support.RootBeanDefinition(
                        com.zaxxer.hikari.HikariDataSource.class));
        beanFactory.registerBeanDefinition("databaseTimeoutConsistency",
                new org.springframework.beans.factory.support.RootBeanDefinition(
                        DatabaseTimeoutConsistency.class));

        new DatabaseTimeoutConsistency.TheBoundsAreCheckedBeforeThePoolIsBuilt()
                .postProcessBeanFactory(beanFactory);

        assertThat(beanFactory.getBeanDefinition("dataSource").getDependsOn())
                .as("without this edge the pool binds first and refuses a zero acquisition bound "
                    + "through validation-timeout, with a message about the wrong property and the "
                    + "wrong problem")
                .contains("databaseTimeoutConsistency");
    }

    // ------------------------------------------------------------------ the two soft rules

    /**
     * <strong>Soft rule A — a waiter must not give up before a holder is entitled to hold.</strong>
     * It fires on the operator action that genuinely invalidates the acquisition bound: raising
     * {@code DB_LOCK_TIMEOUT_MS} and leaving the acquisition bound where it was. A WARN and not a
     * refusal, because an operator who has weighed the two may legitimately want them apart.
     */
    @Test
    void anAcquisitionBoundBelowTheLockBoundWarnsAndStarts() {
        var warnings = warningsFrom(() -> check(SHIPPED_STATEMENT_MS, 5000, "3000"));

        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst())
                .contains("DB_CONNECTION_TIMEOUT_MS")
                .contains("app.locking.lock-timeout-ms");
    }

    /**
     * <strong>Soft rule B — a bound above the stop grace is a bound the environment does not
     * honour.</strong> It replaces {@code POOL_TURNOVER_SHARE}, whose premise (one statement ≈ one
     * connection hold) was never true in this product: {@code BoundedJpaTransactionManager} and
     * {@code PlanningProperties} both said so in as many words, years of reviews apart. 60 000 and
     * 300 000 are exactly the values {@code docs/self-hosting.md} labels diagnostic, which is
     * better guidance than the deleted rule's "anything above 15 000".
     */
    @Test
    void aStatementBoundLongerThanTheStopGraceWarnsAndStarts() {
        for (int diagnostic : new int[]{60_000, 300_000}) {
            var warnings = warningsFrom(() -> check(diagnostic, SHIPPED_LOCK_MS, "3000"));
            assertThat(warnings)
                    .as("a statement bound of %d ms cannot finish inside a 30 s stop grace",
                        diagnostic)
                    .hasSize(1);
            assertThat(warnings.getFirst())
                    .contains("app.persistence.statement-timeout-ms")
                    .contains("APP_STOP_GRACE_SECONDS");
        }
    }

    /**
     * <strong>And the value the deleted rule used to complain about is silent now</strong>, which
     * is the whole difference between a rule about a real anchor and one about a circular
     * derivation: 30 000 was "more than half of Hikari's unset 30 s" and therefore warned, while
     * being a statement bound that fits the grace exactly.
     */
    @Test
    void aThirtySecondStatementBoundNoLongerWarnsAboutAPoolItCannotTurnOver() {
        assertThat(warningsFrom(() -> check(30_000, SHIPPED_LOCK_MS, "3000"))).isEmpty();
    }

    // ------------------------------------------------------------------ plumbing

    /** The shipped family, with only the mail drain moved — the H-3 arithmetic from its other end. */
    private void checkWithDrain(int drainSeconds) {
        new DatabaseTimeoutConsistency(
                new StatementTimeoutProperties(SHIPPED_STATEMENT_MS),
                new LockingProperties(SHIPPED_LOCK_MS),
                mail(drainSeconds, 100, 30),
                environmentWith(String.valueOf(SHIPPED_ACQUISITION_MS))).check();
    }

    private void check(int statementMs, int lockMs, String acquisition) {
        new DatabaseTimeoutConsistency(
                new StatementTimeoutProperties(statementMs),
                new LockingProperties(lockMs),
                mail(15, 100, 30),
                environmentWith(acquisition)).check();
    }

    private static MailAsyncProperties mail(int drainSeconds, int queueCapacity, int graceSeconds) {
        return new MailAsyncProperties(
                new MailAsyncProperties.Async(2, 5, queueCapacity, drainSeconds, graceSeconds),
                null, null);
    }

    private static Environment environmentWith(String acquisition) {
        return new MockEnvironment()
                .withProperty(DatabaseTimeoutConsistency.ACQUISITION_PROPERTY, acquisition);
    }

    /**
     * An environment carrying one <em>environment variable</em>, in the property source Boot's
     * relaxed binding treats as one. The name matters: the binder applies the relaxed
     * environment-variable mapping only to a {@code SystemEnvironmentPropertySource} called
     * {@code systemEnvironment} (or {@code …-systemEnvironment}), so a differently named source
     * would quietly test nothing.
     */
    private static ConfigurableEnvironment environmentWithVariable(String name, String value) {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(
                new org.springframework.core.env.SystemEnvironmentPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        java.util.Map.of(name, value)));
        return environment;
    }

    /** The shipped family, checked, so the seal below has a validated bound to compare against. */
    private static DatabaseTimeoutConsistency checked(String acquisition) {
        var check = new DatabaseTimeoutConsistency(
                new StatementTimeoutProperties(SHIPPED_STATEMENT_MS),
                new LockingProperties(SHIPPED_LOCK_MS),
                mail(15, 100, 30),
                environmentWith(acquisition));
        check.check();
        return check;
    }

    private static DatabaseTimeoutConsistency.ThePoolHoldsTheBoundThatWasChecked seal(
            DatabaseTimeoutConsistency bounds, javax.sql.DataSource dataSource) {
        return new DatabaseTimeoutConsistency.ThePoolHoldsTheBoundThatWasChecked(
                bounds, provider(dataSource));
    }

    /**
     * The seal with more than one candidate and no primary among them — what a deployment that adds
     * a second {@code DataSource} hands it, and the second of the two situations
     * {@code getIfUnique()} reports by returning {@code null}.
     */
    private static DatabaseTimeoutConsistency.ThePoolHoldsTheBoundThatWasChecked sealWithNoPrimary(
            DatabaseTimeoutConsistency bounds, javax.sql.DataSource... candidates) {
        return new DatabaseTimeoutConsistency.ThePoolHoldsTheBoundThatWasChecked(
                bounds, provider(null, List.of(candidates)));
    }

    /**
     * A pool holding the shipped arrangement: <strong>both</strong> Hikari lines on the one number,
     * because that is what {@code application.properties} spells and what the seal checks. Setting
     * only the acquisition line here would leave validationTimeout at Hikari's own 5000 default and
     * every call would refuse for the second reason rather than the one it is testing.
     */
    private static com.zaxxer.hikari.HikariDataSource pool(long connectionTimeoutMs) {
        return pool(connectionTimeoutMs, connectionTimeoutMs);
    }

    private static com.zaxxer.hikari.HikariDataSource pool(long connectionTimeoutMs,
                                                           long validationTimeoutMs) {
        var pool = new com.zaxxer.hikari.HikariDataSource();
        pool.setConnectionTimeout(connectionTimeoutMs);
        pool.setValidationTimeout(validationTimeoutMs);
        return pool;
    }

    /**
     * The {@code ObjectProvider} contract by hand rather than a mock, for the one candidate the seal
     * can resolve — or for none at all, which is every property unit test in this file.
     */
    private static org.springframework.beans.factory.ObjectProvider<javax.sql.DataSource> provider(
            javax.sql.DataSource dataSource) {
        return provider(dataSource, dataSource == null ? List.of() : List.of(dataSource));
    }

    /**
     * <strong>{@code getIfUnique()} answers {@code null} for two different situations, so the fake
     * has to be able to produce both of them.</strong> None at all, and several with no primary: the
     * seal tells them apart by asking {@code stream()}, which is why this is not the one-question
     * stub it used to be. {@code stream()} is a {@code default} method on {@code ObjectProvider}
     * that throws {@code UnsupportedOperationException}, so a fake that leaves it out does not model
     * the ambiguity — it fails the test with the wrong exception.
     */
    private static org.springframework.beans.factory.ObjectProvider<javax.sql.DataSource> provider(
            javax.sql.DataSource unique, List<javax.sql.DataSource> candidates) {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override
            public javax.sql.DataSource getObject() {
                if (unique == null) {
                    throw new org.springframework.beans.factory.NoSuchBeanDefinitionException(
                            javax.sql.DataSource.class);
                }
                return unique;
            }

            @Override
            public javax.sql.DataSource getObject(Object... args) {
                return getObject();
            }

            @Override
            public javax.sql.DataSource getIfAvailable() {
                return unique;
            }

            @Override
            public javax.sql.DataSource getIfUnique() {
                return unique;
            }

            @Override
            public java.util.stream.Stream<javax.sql.DataSource> stream() {
                return candidates.stream();
            }
        };
    }

    /** The real configuration for one profile, placeholders resolved as they are at boot. */
    private static ConfigurableEnvironment configuration(String profile) {
        var environment = new StandardEnvironment();
        ConfigDataEnvironmentPostProcessor.applyTo(environment, null, null, profile);
        return environment;
    }

    /** The WARN lines {@link DatabaseTimeoutConsistency} emitted while {@code body} ran. */
    private List<String> warningsFrom(Runnable body) {
        return warningsFrom(body, DatabaseTimeoutConsistency.class);
    }

    /** The same, for a logger that belongs to one of the nested checks. */
    private List<String> warningsFrom(Runnable body, Class<?> logger0) {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(logger0);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            body.run();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /** The literal default in a {@code key=${VAR:default}} line. */
    private static long propertyDefault(String properties, String key) {
        var matcher = Pattern.compile(Pattern.quote(key) + "=\\$\\{[A-Z0-9_]+:(\\d+)}")
                .matcher(properties);
        if (!matcher.find()) {
            return fail("application.properties no longer declares %s as ${VAR:default}. That form "
                        + "is what lets one .env line move the value, and what the operator "
                        + "documentation promises", key);
        }
        return Long.parseLong(matcher.group(1));
    }

    private static String applicationProperties() throws IOException {
        return new String(new ClassPathResource("application.properties").getInputStream()
                .readAllBytes(), StandardCharsets.UTF_8);
    }
}
