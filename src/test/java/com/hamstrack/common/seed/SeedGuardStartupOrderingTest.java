package com.hamstrack.common.seed;

import com.hamstrack.auth.entity.SystemRole;
import com.hamstrack.auth.entity.User;
import com.hamstrack.auth.entity.UserStatus;
import com.hamstrack.auth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.boot.web.server.servlet.ServletWebServerFactory;
import org.springframework.boot.web.server.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;

/**
 * <strong>HD-200 — the seed guards are sealed at the MOMENT they fire, not only by the words
 * they use.</strong>
 *
 * <p>This test exists because of a regression the whole suite let through. A fix round moved
 * {@link DataSeeder#rejectPublishedPassword} out of {@code @PostConstruct} and into
 * {@code DataSeeder.run} <em>below</em> the blank-email and blank-password returns — which is
 * precisely the arrangement its own javadoc forbids, because those returns are what an
 * already-seeded installation takes — and 1218 tests stayed green. They stayed green because
 * {@code SeedAdminPasswordValidationTest} calls the static method by hand: it asserts what
 * the refusal SAYS and never that anything reaches it. A claim about one member, where the
 * lesson was about a category.
 *
 * <p>So the assertions here are about reachability, and they are phrased over <em>every</em>
 * branch rather than over the branch that broke:
 *
 * <ul>
 *   <li>the CONFIGURATION guard fires during context refresh, before any
 *       {@code ApplicationRunner} exists — the strongest and cheapest statement of "before
 *       any of that";</li>
 *   <li>and, separately, so that a deliberate relocation still meets something that explains
 *       itself: with the published password set, <strong>no</strong> path through
 *       {@code run} completes normally, whatever else is configured;</li>
 *   <li>the STORED-HASH guard fires even when the configuration mentions nothing at all,
 *       which is the only state the compromised installations are actually in;</li>
 *   <li><strong>and it fires at refresh too</strong> — which cost this ticket a round. That
 *       guard was an {@code ApplicationRunner}, i.e. {@code callRunners()}, which Boot
 *       invokes <em>after</em> the refresh that binds the port. Measured on a real boot:
 *       {@code Started HamstrackApplication} at 03:34:53.898, refusal at 03:35:01.088, and a
 *       login as the published-password administrator inside that window returned 200 with a
 *       30-minute access token. {@code restart: unless-stopped} re-opens it on every
 *       crash-loop cycle. So the seal is stated twice, once structurally
 *       ({@link #theStoredHashGuardFiresDuringRefreshBeforeAnyRunner()}) and once as the
 *       consequence that actually matters
 *       ({@link #theInstanceNeverServesWhileAnAdministratorCarriesThePublishedPassword()}:
 *       no {@code WebServerInitializedEvent} is ever published).</li>
 *   <li><strong>and the guard is EAGER, not merely early-annotated</strong> — every case
 *       above instantiates the seeder eagerly by construction, so a bean deferred by
 *       {@code @Lazy} (or by a deployment's {@code spring.main.lazy-initialization}) runs
 *       the same {@code @PostConstruct} from {@code callRunners}, after the port, with all
 *       of them still green. {@link #nothingDefersTheGuardPastThePortBind()} is the one
 *       that notices, and it covers both of those shapes with one assertion: it boots under
 *       {@code spring.main.lazy-initialization=true}, so it reds if the explicit eager pin
 *       on {@code DataSeeder} is deleted OR inverted.</li>
 * </ul>
 *
 * <p>No database: an {@link ApplicationContextRunner} over a mocked repository, which is
 * what makes this cheap enough to be a seal rather than a suite. One case starts a real
 * Tomcat, because "the port never served" is not a claim a mock can make.
 */
class SeedGuardStartupOrderingTest {

    /**
     * Assembled rather than written out: the repository-wide scan in
     * {@code JwtSecretValidationTest} reads this file too, and a literal assignment here
     * would be read as one more place we published it.
     */
    private static final String PUBLISHED = "SEED_ADMIN" + "_PASSWORD";

    /** Strength 4 — a real bcrypt (the guard must verify one), fast enough for a unit test. */
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(4);

    /**
     * Present in the context when the instance under test is supposed to HAVE an
     * administrator carrying the published password. A marker bean rather than a second
     * {@code Stubs} class, so both context runners — the plain one and the servlet one —
     * describe the same instance in the same words.
     */
    record OffendingAdmin(String email) {
    }

    /**
     * The instance, minus the seeder. Split out of {@link Stubs} for one reason: a
     * {@code @Bean} method's laziness is decided by the method, so a seeder defined there
     * cannot see {@code @Lazy}/{@code @Lazy(false)} on {@link DataSeeder} at all. The case
     * that has to see them registers the real class instead, and takes its dependencies
     * from here.
     */
    @Configuration
    static class Dependencies {

        @Bean
        UserRepository userRepository(ObjectProvider<OffendingAdmin> offending) {
            // Mockito answers an empty List (and 0 for a long) for unstubbed methods, so an
            // instance with no offending administrator needs no stubbing at all.
            var repository = Mockito.mock(UserRepository.class);
            OffendingAdmin published = offending.getIfAvailable();
            if (published != null) {
                Mockito.when(repository
                                .findFirst25BySystemRoleAndPasswordHashIsNotNullOrderByCreatedAtAsc(
                                        SystemRole.ADMIN))
                        .thenReturn(List.of(admin(published.email(), ENCODER.encode(PUBLISHED))));
            }
            return repository;
        }

        @Bean
        PasswordEncoder passwordEncoder() {
            return ENCODER;
        }
    }

    /** The same instance with the seeder wired in the way most of these cases want it. */
    @Configuration
    @Import(Dependencies.class)
    static class Stubs {

        @Bean
        DataSeeder dataSeeder(UserRepository repository, PasswordEncoder encoder) {
            return new DataSeeder(repository, encoder);
        }
    }

    /**
     * A real embedded Tomcat on an ephemeral port, plus the one thing worth asserting about
     * it: whether Boot ever got as far as saying the connectors are up.
     */
    @Configuration
    static class WebStubs {

        static final AtomicBoolean SERVED = new AtomicBoolean();

        @Bean
        ServletWebServerFactory servletWebServerFactory() {
            return new TomcatServletWebServerFactory(0);
        }

        @Bean
        ApplicationListener<WebServerInitializedEvent> connectorWatch() {
            return event -> SERVED.set(true);
        }
    }

    /**
     * <strong>Note what this vehicle cannot express.</strong> Spring's property machinery
     * trims a value bound through {@code withPropertyValues}, so
     * {@code seed.admin.password=SEED_ADMIN_PASSWORD } arrives at the guard 19 characters
     * long, not 20 — a whitespace case written here therefore asserts nothing about
     * whitespace and stays green with {@code .strip()} deleted. A real deployment passes the
     * value through an environment variable, which preserves the space, so the property is
     * genuine; the only vehicle that can carry it is the direct call in
     * {@code SeedAdminPasswordValidationTest.surroundingWhitespaceIsNotABypass}, which reds
     * as it should. This test class used to duplicate that case and could not fail for its
     * own stated reason.
     */
    private static ApplicationContextRunner runner(String email, String password) {
        return new ApplicationContextRunner()
                .withUserConfiguration(Stubs.class)
                .withPropertyValues("seed.admin.email=" + email, "seed.admin.password=" + password);
    }

    /**
     * The regression, stated directly: a blank {@code seed.admin.email} is the "skip seeding"
     * branch, and an installation that already has the account is exactly the one that takes
     * it. The refusal has to have happened before anything asks whether to seed.
     */
    @Test
    void theConfigurationGuardFiresDuringRefreshBeforeAnyRunner() {
        runner("", PUBLISHED).run(context -> assertThat(context)
                .withFailMessage("""

                        The published-password guard did not fire while the context was refreshing, \
                        so it now depends on something LATER running - and what runs later is the \
                        branch that skips seeding, which is what every already-seeded installation \
                        does.

                        This is the exact regression this test exists for: the guard was moved out \
                        of @PostConstruct into DataSeeder.run() BELOW the blank-email and \
                        blank-password returns, and the entire suite stayed green, because the only \
                        other test calls the static method by hand. Put it back above every return \
                        - or, better, back into @PostConstruct.""")
                .hasFailed());
    }

    /**
     * The same claim written as a property of the whole class of branches rather than of the
     * one that broke: whatever else is configured, a published password is refused before
     * {@code run} can finish. A relocation that keeps the guard reachable passes this; one
     * that hides it behind any {@code return} does not.
     */
    @Test
    void noPathThroughRunCompletesWhileThePasswordIsPublished() {
        for (String email : List.of("", "  ", "admin@yourdomain.com")) {
            refusalIsUnavoidable(email, PUBLISHED);
        }
    }

    /**
     * <strong>The installations that are actually compromised say nothing about it.</strong>
     * Configuration repaired, or removed years ago; account untouched, because seeding is
     * idempotent and the existing-account branch never re-passwords a user. Nothing in
     * {@code seed.admin.*} can see that, so the guard reads the stored hash instead.
     */
    @Test
    void theStoredHashGuardFiresWhenTheConfigurationMentionsNothing() {
        var seeder = seederWithPublishedAdmin("published@example.com");
        setSeedProperties(seeder, "", "");

        assertThatIllegalStateException()
                .isThrownBy(seeder::refusePublishedCredentials)
                .withMessageContaining("published@example.com")
                .withMessageContaining("STORED password");
    }

    /**
     * <strong>The moment, for the stored-hash half — and it is the item this round was
     * opened by.</strong> This guard lived in {@code run}, an {@code ApplicationRunner}, and
     * Boot calls those from {@code callRunners()} <em>after</em> the refresh that binds the
     * connectors. So "the application will not start" was false in the only sense an
     * attacker cares about: a measured 7.19 s of a fully functional instance, ended by a
     * refusal, and re-opened by {@code restart: unless-stopped} on every crash-loop cycle.
     *
     * <p>An {@link ApplicationContextRunner} never invokes runners, so this case can only
     * pass if the refusal happens while the context is refreshing — the same shape, and the
     * same proof, as the configuration guard's seal above.
     */
    @Test
    void theStoredHashGuardFiresDuringRefreshBeforeAnyRunner() {
        new ApplicationContextRunner()
                .withUserConfiguration(Stubs.class)
                .withBean(OffendingAdmin.class, () -> new OffendingAdmin("published@example.com"))
                .run(context -> assertThat(context)
                        .withFailMessage("""

                                An administrator carrying the published password did not stop the \
                                context from refreshing, so the refusal now depends on something \
                                LATER - and what runs later is an ApplicationRunner, which Boot \
                                invokes AFTER the port is bound.

                                That is not a style point. Measured on a real boot: "Started \
                                HamstrackApplication" at 03:34:53.898, refusal at 03:35:01.088, and \
                                a login as that administrator inside the window returned 200 with a \
                                30-minute access token. `restart: unless-stopped` re-opens the same \
                                window on every cycle of the crash loop, indefinitely.

                                Put the guard back in @PostConstruct (or any refresh-time \
                                callback). "It needs the database" is not a reason to run later: the \
                                repository is injectable and usable there.""")
                        .hasFailed());
    }

    /**
     * <strong>The same claim as the consequence rather than as the mechanism.</strong> The
     * case above says "the context failed to refresh"; this one says the thing an operator
     * was promised — <em>the instance never served a request</em> — using Boot's own signal
     * for it, over a real Tomcat.
     *
     * <p>{@code WebServerInitializedEvent} is published from {@code finishRefresh()}, which
     * is also where {@code TomcatWebServer} adds back the connectors it removed before
     * starting; bean initialisation fails earlier, in
     * {@code finishBeanFactoryInitialization}. So "the event was never published" and "the
     * port was never bound" are the same statement. Move the guard back into a runner and
     * this case fails at the FIRST assertion, because refresh then succeeds.
     *
     * <p><strong>DO NOT DELETE THIS CASE TO SAVE SUITE TIME.</strong> It is the slow one — a
     * real Tomcat, ~1 s where its neighbours cost milliseconds — and it is therefore the
     * obvious candidate the next time somebody trims the suite. It is also the ONLY case here
     * that can tell "refresh failed" apart from "refresh failed BEFORE THE PORT BOUND", and
     * only the second of those is the promise made to the operator. Verified rather than
     * argued: move both guards to {@code @EventListener(ContextRefreshedEvent.class)} and the
     * refusal still propagates out of {@code refresh()}, so
     * {@link #theStoredHashGuardFiresDuringRefreshBeforeAnyRunner()} and its configuration
     * twin stay green — while {@code getLifecycleProcessor().onRefresh()} has already started
     * the web server earlier inside that same {@code finishRefresh()}, and this vehicle
     * measured the connectors accepting connections (3 of them, ~30 ms) before the refusal
     * arrived. Same green/red split as the eagerness case below, for a different reason: two
     * claims that read alike and are not the same claim.
     *
     * <p><strong>SERVED is asserted FIRST, and the root cause is walked by
     * {@link #rootCauseOf} rather than by AssertJ's {@code rootCause()}</strong> — learned
     * from that same relocation, which is the one that makes this case red. An
     * {@code @EventListener}'s exception propagates out of {@code refresh()} UNWRAPPED, and
     * AssertJ's {@code rootCause()} refuses a throwable that has no cause ("expecting a root
     * cause"); ordered the other way this case died on the SHAPE of the failure and never
     * reached the message that explains which promise broke. The refusal is still asserted,
     * after — same order, and for the same reason, as
     * {@link #nothingDefersTheGuardPastThePortBind()}.
     */
    @Test
    void theInstanceNeverServesWhileAnAdministratorCarriesThePublishedPassword() {
        WebStubs.SERVED.set(false);
        var context = new AnnotationConfigServletWebServerApplicationContext();
        context.register(Stubs.class, WebStubs.class);
        context.registerBean(OffendingAdmin.class, () -> new OffendingAdmin("published@example.com"));
        try {
            Throwable refusal = catchThrowable(context::refresh);

            assertThat(WebStubs.SERVED.get())
                    .withFailMessage("""

                            THE PORT SERVED BEFORE THE REFUSAL. A WebServerInitializedEvent was \
                            published, which is Boot saying the connectors are bound and this \
                            instance is accepting requests - while a system administrator on it \
                            carries a password printed in a public repository.

                            This is the exact window that was exploited to open this round: 7.19 s \
                            on the measured boot, a successful login, and a 30-minute admin token \
                            out of an instance that "will not start". Whatever guard moved, move it \
                            back to refresh time: bean initialisation runs before finishRefresh(), \
                            which is where the connectors TomcatWebServer removed are added back.""")
                    .isFalse();
            assertThat(refusal)
                    .describedAs("the context started with a published-password administrator in it")
                    .isNotNull();
            assertThat(rootCauseOf(refusal))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("published@example.com");
        } finally {
            context.close();
        }
    }

    /**
     * <strong>The annotation's effect is sealed above; this is its EAGERNESS.</strong> Every
     * other case in this class depends on {@link DataSeeder} being an eagerly-instantiated
     * singleton, so that {@code @PostConstruct} runs inside
     * {@code finishBeanFactoryInitialization} — and each of them instantiates it eagerly BY
     * CONSTRUCTION, so none of them can notice when that stops being true. Put {@code @Lazy}
     * on the class, or set {@code spring.main.lazy-initialization=true}, and the annotation
     * stays exactly where it is while the callback moves to the moment the bean is first
     * NEEDED — which for an {@code ApplicationRunner} is
     * {@code SpringApplication.callRunners}, the very frame this ticket exists to get off.
     * Measured on this vehicle, with the guard's own code untouched: same call site
     * ({@code callRunners → getBean → InitDestroyAnnotationBeanPostProcessor}), same work,
     * after the same port bind — {@code @Lazy} alone opened a window a poller connected
     * through 6 times, the property opened one it connected through 386 times. The size is
     * not the point (a stub verifies one strength-4 hash where production verifies up to 25
     * at strength 12); the point is that it exists at all. Every other case in this class
     * stayed green through both.
     *
     * <p>So this one boots a real {@code SpringApplication} — the only vehicle that reads
     * bean-definition laziness at all — and registers {@code DataSeeder.class} itself rather
     * than {@link Stubs}' {@code @Bean} method, because a {@code @Bean} method's laziness
     * belongs to the method and the class's own {@code @Lazy} is invisible to it. It also
     * asserts SERVED <em>before</em> it asserts the refusal, so the loud message wins: the
     * other way round, a relocation that throws unwrapped (an {@code @EventListener}, say)
     * reds on AssertJ's {@code rootCause()} shape complaint and the reader never learns which
     * promise broke.
     *
     * <p><strong>It boots under {@code spring.main.lazy-initialization=true}, and that is
     * what makes one case a seal over both shapes.</strong> The property is the half no
     * source edit shows: an operator sets it in {@code application.properties} or as
     * {@code SPRING_MAIN_LAZY_INITIALIZATION}, and it defers a plain {@code @Component}
     * exactly as {@code @Lazy} on the class does. Both are answered by the same one thing in
     * production — {@code @Lazy(false)} on {@link DataSeeder}, which sets that definition's
     * lazy-init flag EXPLICITLY, and
     * {@code LazyInitializationBeanFactoryPostProcessor.postProcess} returns early on any
     * definition whose flag is already set, so the property can no longer reach this bean.
     * Verified in both directions on the settled tree rather than read off the code: green as
     * shipped; red with {@code @Lazy(false)} deleted; red with {@code @Lazy} substituted for
     * it. In each red run this was the ONLY failing case in the class — the other 13 stayed
     * green — which is the whole of why it exists separately from them.
     */
    @Test
    void nothingDefersTheGuardPastThePortBind() {
        WebStubs.SERVED.set(false);
        var application = new SpringApplicationBuilder(Dependencies.class, WebStubs.class, DataSeeder.class)
                .web(WebApplicationType.SERVLET)
                .bannerMode(Banner.Mode.OFF)
                .registerShutdownHook(false)
                .properties("spring.main.lazy-initialization=true")
                .initializers(context -> ((GenericApplicationContext) context).registerBean(
                        OffendingAdmin.class, () -> new OffendingAdmin("published@example.com")))
                .build();

        Throwable refusal = null;
        try (var context = application.run()) {
            // Started clean: asserted below, after the assertion that actually matters.
        } catch (Throwable thrown) {
            refusal = thrown;
        }

        assertThat(WebStubs.SERVED.get())
                .withFailMessage("""

                        THE PORT SERVED BEFORE THE REFUSAL - and the guard's own code is probably \
                        untouched. What moved is WHEN it runs.

                        DataSeeder's refusal is a @PostConstruct, which protects the port only while \
                        the bean is instantiated EAGERLY, during finishBeanFactoryInitialization. \
                        Defer the bean - @Lazy on the class, or spring.main.lazy-initialization=true \
                        - and the same annotation fires from SpringApplication.callRunners instead, \
                        which is AFTER the connectors are bound: the exact frame this ticket was \
                        opened to get off, and the 7.19/7.34/7.96 s window of working login as the \
                        published-password administrator, reproduced with nothing in the guard \
                        changed. Every other case in this class passes while this one fails, because \
                        they all instantiate the seeder eagerly by construction.

                        If you are here because startup is slow (the stored-hash probe is up to ~6.9 s \
                        of strength-12 bcrypt), deferring THIS bean is the one fix that is not \
                        available. Make the probe cheaper, or pin the bean eager explicitly - \
                        @Lazy(false), which is the flag LazyInitializationBeanFactoryPostProcessor \
                        leaves alone, or a LazyInitializationExcludeFilter for it.""")
                .isFalse();
        assertThat(refusal)
                .describedAs("the instance started with a published-password administrator in it")
                .isNotNull();
        assertThat(rootCauseOf(refusal))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("published@example.com");
    }

    private static Throwable rootCauseOf(Throwable thrown) {
        Throwable root = thrown;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root;
    }

    /**
     * The intuitive repair — new password in {@code .env}, restart — and the clean boot that
     * used to follow it while the account kept the published password. This is the state an
     * operator will actually be in, and the one the configuration guard cannot see.
     */
    @Test
    void theStoredHashGuardFiresAfterTheFileIsRepairedInsteadOfTheAccount() {
        var seeder = seederWithPublishedAdmin("admin@corp.example");
        setSeedProperties(seeder, "admin@corp.example", "k7Qv2#tR9pLm4zXw");

        assertThatIllegalStateException()
                .isThrownBy(seeder::refusePublishedCredentials)
                .withMessageContaining("admin@corp.example");
    }

    /**
     * The refusal has to carry a reader who did nothing wrong today all the way to repaired,
     * and it fires at the one moment the admin console is unreachable, because the
     * application will not start. So it must prescribe something performable against the
     * database, and then the way back.
     */
    @Test
    void theStoredHashRefusalIsActionableWithTheApplicationDown() {
        var seeder = seederWithPublishedAdmin("admin@corp.example");
        setSeedProperties(seeder, "", "");

        String message = refusalFrom(seeder);
        assertThat(message)
                .withFailMessage("""

                        The stored-hash refusal stops the application, which is the one moment its \
                        reader cannot use the admin console. A remedy they cannot perform is not a \
                        remedy: it has to name something that works against the DATABASE, and then \
                        the way back to a normal password.

                        Message was: %s""", message)
                .contains("UPDATE users SET password_hash = NULL")
                .contains("Forgot password");
        assertThat(message)
                .withFailMessage("""

                        This refusal can fire on an installation whose operator changed nothing \
                        today - they upgraded into it. It has to say that configuration is not what \
                        it is reading, or they will edit .env again and conclude the release is \
                        broken.

                        Message was: %s""", message)
                .contains("STORED password")
                .contains("idempotent");
    }

    /** Nothing published anywhere: the application starts and seeds exactly as before. */
    @Test
    void anInstanceWithNothingPublishedStartsAndStillSeeds() {
        runner("admin@corp.example", "k7Qv2#tR9pLm4zXw").run(context -> {
            assertThat(context).hasNotFailed();
            var repository = context.getBean(UserRepository.class);
            Mockito.when(repository.findByEmail("admin@corp.example")).thenReturn(Optional.empty());

            context.getBean(DataSeeder.class).run(null);

            Mockito.verify(repository).save(any(User.class));
        });
    }

    /** And a blank email still skips, rather than being turned into a refusal by any of this. */
    @Test
    void aBlankEmailStillSkipsSeeding() {
        runner("", "k7Qv2#tR9pLm4zXw").run(context -> {
            assertThat(context).hasNotFailed();
            context.getBean(DataSeeder.class).run(null);
            Mockito.verify(context.getBean(UserRepository.class), Mockito.never()).save(any(User.class));
        });
    }

    /**
     * Refused at refresh, or refused by {@code run} — but refused. Which of the two is the
     * subject of {@link #theConfigurationGuardFiresDuringRefreshBeforeAnyRunner()}; this is
     * about there being no third option.
     */
    private static void refusalIsUnavoidable(String email, String password) {
        runner(email, password).run((AssertableApplicationContext context) -> {
            if (context.getStartupFailure() != null) {
                return;
            }
            assertThatIllegalStateException()
                    .describedAs("seed.admin.email=`%s`, published password set: the context started, "
                            + "so run() was the last chance to refuse - and it did not", email)
                    .isThrownBy(() -> context.getBean(DataSeeder.class).run(null))
                    .withMessageContaining(".env.prod.example");
        });
    }

    private static String refusalFrom(DataSeeder seeder) {
        try {
            seeder.refusePublishedCredentials();
        } catch (IllegalStateException refused) {
            return refused.getMessage();
        }
        throw new AssertionError("The administrator carrying the published password was accepted - see "
                + "theStoredHashGuardFiresWhenTheConfigurationMentionsNothing");
    }

    /**
     * <strong>The bound has a hole and the hole is plugged.</strong> The probe reads the
     * oldest administrators, because the account it exists for is created by the installer
     * on first boot. An install that only started seeding years in has that account at the
     * other end of the ordering — so whatever {@code seed.admin.email} names today is looked
     * up by name as well, and this is the case that proves the second lookup is doing work
     * rather than duplicating the first.
     */
    @Test
    void theConfiguredAccountIsCheckedEvenWhenItIsNotAmongTheOldest() {
        var repository = Mockito.mock(UserRepository.class);
        // The oldest administrators are all fine - the ordered query returns nothing of
        // interest, exactly as it would on an install with 25 older admins.
        Mockito.when(repository
                        .findFirst25BySystemRoleAndPasswordHashIsNotNullOrderByCreatedAtAsc(SystemRole.ADMIN))
                .thenReturn(List.of());
        Mockito.when(repository.findByEmail("late@corp.example"))
                .thenReturn(Optional.of(admin("late@corp.example", ENCODER.encode(PUBLISHED))));

        var seeder = new DataSeeder(repository, ENCODER);
        setSeedProperties(seeder, "Late@Corp.Example", "k7Qv2#tR9pLm4zXw");

        assertThatIllegalStateException()
                .describedAs("""

                        The account seed.admin.email names today was not checked. The ordered query \
                        alone cannot reach it - an install that began seeding late has that account \
                        newest, not oldest - so removing the by-name lookup makes the guard blind to \
                        every install of that shape while every other test still passes.""")
                .isThrownBy(seeder::refusePublishedCredentials)
                .withMessageContaining("late@corp.example");
    }

    /**
     * The bound itself, asserted as a number the reader can see, because it was learned the
     * expensive way: unbounded, one bcrypt per administrator, this added over two minutes to
     * every startup against a database with 1362 of them and the test suite stopped
     * finishing. Raising it is a deliberate trade against boot time, not a tidy-up.
     */
    @Test
    void theProbeIsBounded() {
        assertThat(DataSeeder.ADMINS_PROBED)
                .withFailMessage("""

                        DataSeeder.ADMINS_PROBED changed. This number is startup time paid by every \
                        installation: measured at ~370 ms per verification at the strength \
                        SecurityConfig configures (12 - NOT the ~100 ms of strength 10 this message \
                        used to claim), and the loop runs once per (administrator x published \
                        password), so 25 is ~6.9 s today and doubles the day a second entry joins \
                        PUBLISHED_PASSWORDS. The repository method name pins the same number a \
                        second time (findFirst25By...), so the two must be changed together or the \
                        constant becomes a comment.""")
                .isEqualTo(25);
    }

    /**
     * <strong>The one signal that a check was PARTIAL — and it was defended by nothing.</strong>
     * Deleting the whole {@code if (…) log.warn(…)} block used to red zero tests, while that
     * block is the only thing standing between "some administrators were verified" and "all
     * of them were" in the operator's eyes.
     */
    @Test
    void aPartialCheckSaysSoOutLoudRatherThanNarrowingSilently() {
        var repository = Mockito.mock(UserRepository.class);
        Mockito.when(repository
                        .findFirst25BySystemRoleAndPasswordHashIsNotNullOrderByCreatedAtAsc(SystemRole.ADMIN))
                .thenReturn(harmlessAdministrators(25));
        Mockito.when(repository.countBySystemRoleAndPasswordHashIsNotNull(SystemRole.ADMIN)).thenReturn(30L);

        var seeder = new DataSeeder(repository, ENCODER);
        setSeedProperties(seeder, "", "");

        assertThat(warningsFrom(seeder))
                .withFailMessage("""

                        Nothing told the operator that the published-password check was PARTIAL. \
                        The probe is bounded, so on a large install it verifies some administrators \
                        and not others - and this WARN is the only thing that distinguishes "checked \
                        and clean" from "checked 25 of them and clean". Deleting it reds no other \
                        test, which is exactly why this one exists.""")
                .anySatisfy(warning -> assertThat(warning)
                        .contains("25")
                        .contains("30")
                        .containsIgnoringCase("not verified"));
    }

    /**
     * <strong>And it must not cry wolf.</strong> The gate counted the WHOLE role while the
     * probe only ever covers administrators that HAVE a password — so an install with 26
     * administrators of whom 22 carry a password, every one of them verified, was still told
     * that "the remainder were not verified", and was told the constant (25) rather than the
     * 22 actually probed. It could never produce a false negative, so this is message
     * quality; a partial-coverage warning that fires on complete coverage is how the warning
     * gets tuned out, which is a false negative by another route.
     */
    @Test
    void completeCoverageIsSilentEvenWhenAdministratorsOutnumberTheBound() {
        var repository = Mockito.mock(UserRepository.class);
        Mockito.when(repository
                        .findFirst25BySystemRoleAndPasswordHashIsNotNullOrderByCreatedAtAsc(SystemRole.ADMIN))
                .thenReturn(harmlessAdministrators(22));
        // 26 administrators, 22 of them with a password - and all 22 were just verified.
        Mockito.when(repository.countBySystemRoleAndPasswordHashIsNotNull(SystemRole.ADMIN)).thenReturn(22L);

        var seeder = new DataSeeder(repository, ENCODER);
        setSeedProperties(seeder, "", "");

        assertThat(warningsFrom(seeder))
                .withFailMessage("""

                        A partial-coverage warning fired on COMPLETE coverage: every administrator \
                        that has a password was verified. This happens when the gate counts the \
                        whole ADMIN role instead of the population the probe can examine \
                        (countBySystemRoleAndPasswordHashIsNotNull) - administrators with no \
                        password are unverifiable, not unverified.""")
                .isEmpty();
    }

    /** Distinct administrators, none of them carrying anything this project published. */
    private static List<User> harmlessAdministrators(int count) {
        String hash = ENCODER.encode("k7Qv2#tR9pLm4zXw");
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> admin("admin" + i + "@corp.example", hash))
                .toList();
    }

    /**
     * The WARN lines this seeder emits, read off the real logger — the message is the whole
     * mechanism here, so asserting on anything else would be asserting on a stand-in.
     */
    private static List<String> warningsFrom(DataSeeder seeder) {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(DataSeeder.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            seeder.refusePublishedCredentials();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        return appender.list.stream()
                .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private static User admin(String email, String passwordHash) {
        var user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setDisplayName("Admin");
        user.setPasswordHash(passwordHash);
        user.setStatus(UserStatus.ACTIVE);
        user.setSystemRole(SystemRole.ADMIN);
        return user;
    }

    private static DataSeeder seederWithPublishedAdmin(String email) {
        var repository = Mockito.mock(UserRepository.class);
        var admin = admin(email, ENCODER.encode(PUBLISHED));
        Mockito.when(repository
                        .findFirst25BySystemRoleAndPasswordHashIsNotNullOrderByCreatedAtAsc(SystemRole.ADMIN))
                .thenReturn(List.of(admin));
        return new DataSeeder(repository, ENCODER);
    }

    /**
     * {@code @Value}-bound fields, set directly because these cases construct the seeder
     * without a context on purpose: the state they describe is one no property expresses.
     */
    private static void setSeedProperties(DataSeeder seeder, String email, String password) {
        set(seeder, "adminEmail", email);
        set(seeder, "adminDisplayName", "Admin");
        set(seeder, "adminPassword", password);
    }

    private static void set(DataSeeder seeder, String field, String value) {
        try {
            var declared = DataSeeder.class.getDeclaredField(field);
            declared.setAccessible(true);
            declared.set(seeder, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("DataSeeder." + field + " was renamed; this test binds it directly "
                    + "because it exercises states no configuration can express", e);
        }
    }
}
