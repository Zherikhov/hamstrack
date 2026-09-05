package com.hamstrack.common.config;

import com.hamstrack.HamstrackApplication;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <strong>The post-refresh seal stops a REAL boot, not just a method call</strong> (HD-233).
 *
 * <p>{@link DatabaseTimeoutFamilyTest} asserts what
 * {@link DatabaseTimeoutConsistency.ThePoolHoldsTheBoundThatWasChecked} decides; this asserts that
 * anything decides it. The distinction is the review finding that produced the seal in the first
 * place: {@code thePoolIsBuiltAfterTheBoundsAreChecked} exercises a hand-built bean factory, so it
 * proves the {@code depends-on} edge's arithmetic and not that the real context orders that way — a
 * guard whose wiring is unproven is a guard that can be deleted by an annotation. So this one boots
 * the application.
 *
 * <p>The mismatch is forced the only way a test can force one without an environment variable: a
 * {@link BeanPostProcessor} moves the pool's own {@code connectionTimeout} after it is built and
 * before anything reads it. That stands in for every real way the two can diverge — a relaxed
 * environment spelling Boot binds and {@code Environment.getProperty} does not, a
 * {@code DataSourceProperties} customiser, a second property source — because the seal does not ask
 * <em>how</em> they came to differ. It asks the pool what it is holding.
 *
 * <p>A hand-built context rather than {@code @SpringBootTest}: the assertion is that startup
 * <strong>fails</strong>, and the test-context framework caches contexts by configuration and would
 * hand this broken one to whoever asked for the same key next. {@code WebApplicationType.NONE}
 * keeps it to the part under test — the pool is still built, because JPA needs it, which is the
 * whole premise.
 *
 * <p><strong>Except for {@link #theSealStopsTheBootBEFORETheConnectorEverOpens}, and the exception
 * is the point of it.</strong> A refusal that arrives after the connector is listening is not the
 * same refusal: Boot starts the web server from {@code WebServerStartStopLifecycle}, driven out of
 * {@code finishRefresh()} immediately <em>before</em> {@code ContextRefreshedEvent} is published, so
 * a seal hung on that event refuses a boot that can already be accepting — and being routed —
 * requests. A {@code WebApplicationType.NONE} case cannot see that, because there is no connector to
 * open either way, which is exactly how it went unnoticed.
 *
 * <p><strong>A seal is worth what it is worth on the deployment that runs it, so two things about
 * it are asserted rather than assumed: WHEN it decides, and THAT it decides at all.</strong>
 * {@link #theSealStillRefusesUnderGlobalLazyInitialization} owns the second — every other case here
 * instantiates the seal eagerly by construction and so cannot notice a definition that stops being
 * instantiated at all.
 */
class PoolBoundSealBootTest {

    /** Far from the shipped 3000 and legal for Hikari, so only the seal can object to it. */
    private static final long MOVED_TO_MS = 7777;

    @Test
    @Timeout(180)
    void aBootWhosePoolHoldsADifferentBoundStops() {
        assertThatThrownBy(() -> boot(MovePoolBound.class).close())
                .as("""
                    THE SEAL MUST STOP A REAL BOOT.

                    Every rule in DatabaseTimeoutConsistency reads a property; this one reads the \
                    built HikariDataSource, which is what makes acquisitionBoundMs() a fact rather \
                    than an assertion. If the context starts here, the seal is no longer wired - \
                    an @EventListener that nothing publishes to, or a bean no longer scanned - and \
                    the instance will happily report a bound it is not using, in the WARN beside \
                    every 503 DATABASE_BUSY and in the ceiling that keeps the mail shutdown inside \
                    APP_STOP_GRACE_SECONDS.""")
                // Over the whole stack trace rather than the root cause: whether Spring propagates
                // a listener's exception bare or wrapped is Spring's decision and not this code's,
                // and it propagates it bare today. What has to be true is that the boot stopped and
                // that the operator can read why.
                .hasStackTraceContaining(String.valueOf(MOVED_TO_MS))
                .hasStackTraceContaining("SPRING_DATASOURCE_HIKARI_CONNECTIONTIMEOUT")
                .hasStackTraceContaining("was validated as");
    }

    /**
     * And the control, without which the test above passes just as well when the boot fails for an
     * unrelated reason — a missing database, a property this file did not set. Same context, same
     * properties, no tampering.
     */
    @Test
    @Timeout(180)
    void theSameBootWithoutTheTamperingStarts() {
        try (var context = boot(null)) {
            assertThat(context.getBean(DatabaseTimeoutConsistency.class).acquisitionBoundMs())
                    .as("and the bound it reports is the one the pool holds, which is the property "
                        + "the seal exists to keep true")
                    .isEqualTo(context.getBean(HikariDataSource.class).getConnectionTimeout());
        }
    }

    /**
     * <strong>The seal must fire before the connector opens, not merely before the context is
     * usable.</strong> An instance that has begun listening is one a load balancer can route to and
     * a rolling deploy will count as up; it then dies, which on Cloud is a flap rather than a
     * refusal and on either mode is a window in which real requests are served by an instance whose
     * every boot-time bound was computed against a number that is not in force. Every other rule in
     * {@link DatabaseTimeoutConsistency} refuses before the pool is even built, and this one has to
     * hold to the same standard.
     *
     * <p>{@code WebServerInitializedEvent} is the observable: it is published by
     * {@code WebServerStartStopLifecycle} the moment the connector is up. If it fires at all, the
     * seal ran too late — which is precisely what an
     * {@code @EventListener(ContextRefreshedEvent.class)} did, since {@code finishRefresh()} starts
     * the lifecycle beans and then publishes that event. The fix is
     * {@code SmartInitializingSingleton}, which runs at the end of singleton pre-instantiation: the
     * pool exists, so the premise holds, and nothing has been started.
     */
    @Test
    @Timeout(240)
    void theSealStopsTheBootBEFORETheConnectorEverOpens() {
        var connectorOpened = new java.util.concurrent.atomic.AtomicBoolean();

        assertThatThrownBy(() -> bootServlet(connectorOpened).close())
                .as("the premise: the same tampering, in a context that would really open a port")
                .hasStackTraceContaining("was validated as");

        assertThat(connectorOpened)
                .as("""
                    A BOOT REFUSED BY THIS SEAL MUST NEVER HAVE LISTENED.

                    WebServerInitializedEvent fired, so Tomcat had already accepted its port \
                    before the seal objected. Anything that health-checks by connecting - a load \
                    balancer, a rolling deploy, docker-compose - sees an instance that is up, \
                    routes to it, and gets served by a process whose acquisition bound, mail \
                    shutdown ceiling and 503 WARN were all computed from a number the pool is not \
                    holding. The seal belongs in SmartInitializingSingleton (end of singleton \
                    pre-instantiation), NOT on ContextRefreshedEvent, which Boot publishes after \
                    finishRefresh() has already started the web server.""")
                .isFalse();
    }

    /**
     * <strong>A property an operator can set from outside this repository must not be able to
     * delete the seal, and whether it can is a fact about somebody else's code — so it is measured
     * here rather than reasoned about.</strong> {@code spring.main.lazy-initialization=true}
     * ({@code SPRING_MAIN_LAZY_INITIALIZATION}, which flows through {@code .env} like every other
     * {@code SPRING_*} name) defers a plain {@code @Component}, and a deferred startup rule is not
     * late — for a bean nothing else injects it never runs at all, with no refusal, no WARN and no
     * log line. That is the <em>fails open in silence</em> shape the seal's own javadoc lists as a
     * hole it exists to close, and every other case in this class is blind to it: each one
     * instantiates the seal eagerly by construction.
     *
     * <p>It holds, and it holds for a mechanism worth naming because it is not the obvious one.
     * {@link DatabaseTimeoutConsistency.ThePoolHoldsTheBoundThatWasChecked} is a
     * {@link org.springframework.beans.factory.SmartInitializingSingleton}, whose callback exists
     * only for singletons that were pre-instantiated — so the shape looks defeated by that
     * property, and the {@code @EventListener} it replaced
     * ({@link #theSealStopsTheBootBEFORETheConnectorEverOpens}) looks immune, since
     * {@code EventListenerMethodProcessor} resolves the listener's bean by name at publish time.
     * Neither is true: {@code LazyInitializationBeanFactoryPostProcessor.getFilters} adds
     * {@code LazyInitializationExcludeFilter.forBeanTypes(SmartInitializingSingleton.class)} to its
     * own filter list, and its class javadoc gives the reason — "to ensure that their callback
     * method is invoked". The earlier moment therefore cost nothing in eagerness. Read off
     * spring-boot 4.1.0 and, more to the point, asserted here.
     *
     * <p><strong>What this case reds for:</strong> an explicit {@code @Lazy} on the seal (an
     * explicitly set lazy flag is checked <em>before</em> the exclusions and wins), a refactor that
     * moves the callback off {@code SmartInitializingSingleton} onto a shape the exclusion does not
     * cover, or a Boot upgrade that drops the exclusion. Only the last of those is invisible to
     * every other case here, and it is the one no edit to this repository can stage — which is the
     * whole reason this is a boot rather than an assertion about an annotation. Measured, not
     * asserted: adding {@code @Lazy} to the seal on the settled tree reds this case <em>and two of
     * its siblings</em>, because a bean that is explicitly lazy and that nothing injects is never
     * instantiated in any boot, property or no property.
     * {@code SeedGuardStartupOrderingTest.nothingDefersTheGuardPastThePortBind} is the same
     * question asked of {@code DataSeeder}, which — being an ordinary {@code @Component} — needs
     * {@code @Lazy(false)} to answer it, as {@link DatabaseTimeoutConsistency} itself does.
     */
    @Test
    @Timeout(240)
    void theSealStillRefusesUnderGlobalLazyInitialization() {
        Throwable refusal = null;
        try (var context = bootLazy()) {
            // Asserted below: reaching here at all is the failure.
        } catch (Throwable thrown) {
            refusal = thrown;
        }

        assertThat(refusal)
                .withFailMessage("""

                        THE SEAL WAS DELETED BY A PROPERTY - and its own code may be untouched.

                        spring.main.lazy-initialization=true (SPRING_MAIN_LAZY_INITIALIZATION, \
                        reachable from .env) no longer reaches \
                        ThePoolHoldsTheBoundThatWasChecked's DEFINITION, so its callback never ran: \
                        the pool kept a bound nobody checked, the boot succeeded, and there is no \
                        refusal, no WARN and no log line to notice by.

                        What normally holds this: LazyInitializationBeanFactoryPostProcessor \
                        excludes SmartInitializingSingleton bean types from that property on \
                        purpose ("to ensure that their callback method is invoked"). Three things \
                        defeat it - an explicit @Lazy on the class, which is checked BEFORE the \
                        exclusions; a refactor that moves the callback off \
                        SmartInitializingSingleton; a Boot upgrade that drops the exclusion. Check \
                        those in that order.

                        The remedy is never a later callback. @EventListener(ContextRefreshedEvent) \
                        is deferral-proof for an unrelated reason and runs AFTER the connector is \
                        listening, which is the failure the case above this one owns. Keep the \
                        moment and pin the definition: @Lazy(false) on the bean, or a \
                        LazyInitializationExcludeFilter for it. Anything added to \
                        DatabaseTimeoutConsistency whose worth is WHEN it runs needs an answer to \
                        this question of its own.""")
                .isNotNull();
        assertThat(refusal)
                .as("and it must still be the seal refusing, with the operator-facing message")
                .hasStackTraceContaining(String.valueOf(MOVED_TO_MS))
                .hasStackTraceContaining("was validated as");
    }

    private static ConfigurableApplicationContext boot(Class<?> tampering) {
        var builder = new SpringApplicationBuilder(HamstrackApplication.class)
                .web(WebApplicationType.NONE)
                .properties("app.demo.seed-on-first-login=false",
                            "seed.admin.email=",
                            "spring.main.banner-mode=off");
        if (tampering != null) {
            builder = builder.sources(tampering);
        }
        return builder.run();
    }

    /**
     * The same tampered boot as {@link #boot(Class)}, with the one property that changes when every
     * bean in it is instantiated. {@code WebApplicationType.NONE} again: what is under test here is
     * whether the seal runs at all, and the moment it runs at is already sealed one case above.
     */
    private static ConfigurableApplicationContext bootLazy() {
        return new SpringApplicationBuilder(HamstrackApplication.class)
                .web(WebApplicationType.NONE)
                .sources(MovePoolBound.class)
                .properties("app.demo.seed-on-first-login=false",
                            "seed.admin.email=",
                            "spring.main.lazy-initialization=true",
                            "spring.main.banner-mode=off")
                .run();
    }

    /**
     * The same tampered boot as a real servlet application, on ephemeral ports so it cannot collide
     * with a developer's running instance or with a parallel fork. {@code port=0} is what makes
     * this affordable: a connector that opens costs nothing, and whether it opened is the whole
     * assertion.
     */
    private static ConfigurableApplicationContext bootServlet(
            java.util.concurrent.atomic.AtomicBoolean connectorOpened) {
        return new SpringApplicationBuilder(HamstrackApplication.class)
                .web(WebApplicationType.SERVLET)
                .sources(MovePoolBound.class)
                .properties("app.demo.seed-on-first-login=false",
                            "seed.admin.email=",
                            "server.port=0",
                            "management.server.port=0",
                            "spring.main.banner-mode=off")
                .listeners((org.springframework.context.ApplicationListener<
                        org.springframework.boot.web.server.context.WebServerInitializedEvent>)
                        event -> connectorOpened.set(true))
                .run();
    }

    /**
     * Moves the value the pool holds without touching the property the check reads — the shape of
     * every divergence the seal is for, reduced to two lines. It runs before the pool is sealed
     * (Hikari refuses this setter only once the first connection has been taken), and before the
     * seal runs, which is at the end of refresh.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class MovePoolBound implements BeanPostProcessor {

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (bean instanceof HikariDataSource pool) {
                pool.setConnectionTimeout(MOVED_TO_MS);
            }
            return bean;
        }
    }
}
