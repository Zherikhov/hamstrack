package com.hamstrack.ops;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>HD-199 — the security- and resource-load-bearing declarations in
 * {@code docker-compose.prod.yml} cannot be deleted quietly.</strong>
 *
 * <p>(Deliberately not "the four". A leading count goes stale one entry before the list
 * does, and this one already gained a fifth in HD-207.)
 *
 * <p>Each of them is a line in a YAML file that no other test reads, that no compiler
 * checks, and whose absence is invisible until somebody measures the running container.
 * Three of them were absent from production for six weeks — not because anyone removed
 * them, but because nothing shipped the file. This test does not prove the box has the
 * setting; nothing in this repository can. It proves the <em>line</em> cannot vanish from
 * the released file without a red build, which is a different and also necessary thing.
 *
 * <p><strong>What this is not.</strong> It is not a substitute for
 * {@code AuthRateLimitForwardedForTrustedTest#distinctRightmostXffGetIndependentBudgets}
 * (which proves the <em>code</em> maps an address to a key), nor for the two-address probe
 * in {@code docs/ops-prod-hardening.md} §7 (which proves the setting is in effect on the
 * running container). The claim "each visitor gets their own auth budget" is a conjunction
 * of three, and none of the three artefacts stands in for the others.
 *
 * <p>Parsed with SnakeYAML rather than matched with a regex: a comment mentioning
 * {@code mem_limit} must not satisfy a check for {@code mem_limit}, and this file is
 * mostly comments on purpose.
 */
class ProdComposeContractTest {

    private static final Path COMPOSE = Path.of("docker-compose.prod.yml");

    /**
     * The failure message is the propagation checklist, deliberately rather than a comment:
     * whoever trips this is editing the compose file, and what they need is what the line
     * was for and what else moves with it.
     */
    private static final String CHECKLIST = """

            docker-compose.prod.yml is the file a deploy PLACES on the production box \
            (ops/deploy/synced-paths.txt), so a declaration removed here is removed from \
            production at the next merge — silently, because nothing else reads this file.

            Each sealed declaration, what it does, and what else has to change with it:

              * image: ...:${APP_IMAGE_TAG:-latest} — the rollback lever. The pin lives in \
            /opt/hamstrack/.env, which no deploy touches, so it survives by construction. \
            Hard-coding a tag here re-creates the trap docs/release-checklist.md was \
            rewritten to remove: a pin in a synced file is wiped by the next deploy. \
            Moving it also touches .env.prod.example, docs/self-hosting.md#upgrading and \
            the DeployImagePinned alert, which reads APP_IMAGE_TAG.

              * RATE_LIMIT_TRUST_FORWARDED_FOR — behind Caddy, request.getRemoteAddr() is \
            the proxy, one address for every visitor, so without this the 15/minute auth \
            budget is ONE budget for everybody and sixteen login attempts a minute lock out \
            the world. Either "true" or "${RATE_LIMIT_TRUST_FORWARDED_FOR:-true}" satisfies \
            this seal, and the DEFAULT FORM IS STILL A SEAL: what must not be deletable is \
            the deployment's opinion, not the literal. Delete the line and the value falls \
            back to the APP's default, which is false — the one-budget-for-everybody \
            failure, arriving silently. What the default form adds is that an operator can \
            turn it OFF from .env, which a literal here cannot (a value under `environment:` \
            wins over `env_file:`) — and .env.prod.example instructs exactly that reader, \
            the one who publishes the app port and fronts it with their own proxy. A STRING \
            either way: YAML would read a bare true as a boolean and compose rejects a \
            non-string environment value.

              * mem_limit — the other half of the image's -XX:MaxRAMPercentage=50. Without a \
            container limit the JVM takes the percentage against HOST RAM, so the heap floats \
            with whatever machine you deploy on (HD-152). Removing it does not fail anything; \
            it silently un-bounds the heap.

              * healthcheck on app — what makes caddy's `condition: service_healthy` mean \
            anything. Without it nothing distinguishes "the container is up" from "the \
            application is serving", and the ordering this file declares is not in force.

              * stop_grace_period on app — how long the JVM gets between SIGTERM and \
            SIGKILL (HD-207). Docker's default is TEN seconds, which is shorter than the \
            mail executor's 15s shutdown drain, so without this line a deploy kills the \
            process mid-drain and every queued account-critical email — password resets \
            and verifications whose rows are COMMITTED and whose users have already been \
            told to check their inbox — is lost with no failed_email row and no log line. \
            The value is a CONTRACT: the app binds the SAME ${APP_STOP_GRACE_SECONDS} as \
            app.mail.async.stop-grace-seconds and refuses the boot when the drain plus \
            the residue write would not fit inside it. That is why the line must stay \
            the variable form and not a literal — a literal is a number only this file \
            knows, and the app would go on believing its own default while the container \
            was killed at another.

            Full reasoning: docs/design/config-delivery-proposal.md §10.2.
            """;

    @Test
    void prodComposeKeepsItsLoadBearingDeclarations() throws IOException {
        var app = appService();
        var missing = new ArrayList<String>();

        // The image tag must be resolved through the variable, not written into the file.
        var image = String.valueOf(app.get("image"));
        if (!image.contains("${APP_IMAGE_TAG:-latest}")) {
            missing.add("app.image does not resolve the tag through ${APP_IMAGE_TAG:-latest} (found: " + image + ")");
        }

        // Deliberately read from the `environment:` block and not from `.env`: what is
        // sealed is that the DEPLOYMENT declares the posture, so that an operator who sets
        // nothing still gets it. Two spellings do that — the literal, and the interpolation
        // whose default is the same value. The second is the one in the file, because a
        // literal here cannot be overridden from .env at all, and a self-hoster who
        // publishes the app port behind their own proxy must be able to turn it off.
        // A missing line is the failure this seal exists for: the app's own default is
        // false, i.e. one auth budget shared by every visitor behind the proxy.
        Object trust = environment(app).get("RATE_LIMIT_TRUST_FORWARDED_FOR");
        if (!"true".equals(trust) && !"${RATE_LIMIT_TRUST_FORWARDED_FOR:-true}".equals(trust)) {
            missing.add("app.environment.RATE_LIMIT_TRUST_FORWARDED_FOR is neither the string \"true\" nor "
                    + "\"${RATE_LIMIT_TRUST_FORWARDED_FOR:-true}\" (found: " + trust + ")");
        }

        if (app.get("mem_limit") == null) {
            missing.add("app has no mem_limit");
        }
        if (!(app.get("healthcheck") instanceof Map<?, ?> hc) || hc.get("test") == null) {
            missing.add("app has no healthcheck with a `test`");
        }

        // Presence only, here. Whether it is still the ${APP_STOP_GRACE_SECONDS:-N}s form,
        // and whether that N is the number application.properties falls back to, is checked
        // in MailAsyncPropertiesTest next to the arithmetic that consumes it — that is the
        // drift that matters (a grace SHORTER than the app believes it has boots clean,
        // passes its own startup assertion, and is killed mid-drain).
        if (app.get("stop_grace_period") == null) {
            missing.add("app has no stop_grace_period");
        }

        assertThat(missing)
                .withFailMessage(CHECKLIST + "\nMissing or changed: " + missing)
                .isEmpty();
    }

    /**
     * The variable is only a lever while nothing else pins the image. A second, hard-coded
     * tag anywhere in the services block would win for that service and make the rollback
     * advice wrong for exactly one of them — the shape of failure this whole ticket exists
     * to stop shipping.
     */
    @Test
    void noServicePinsTheHamstrackImageOutsideTheVariable() throws IOException {
        List<String> pinned = services().values().stream()
                .filter(Map.class::isInstance)
                .map(s -> String.valueOf(((Map<?, ?>) s).get("image")))
                .filter(i -> i.contains("/hamstrack:") && !i.contains("${APP_IMAGE_TAG"))
                .toList();

        assertThat(pinned)
                .withFailMessage(CHECKLIST + "\nHard-coded hamstrack image tag(s): " + pinned)
                .isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> services() throws IOException {
        // Repository-root-relative: surefire runs with the module directory as its working
        // directory, the same way the source-reading seal tests in this suite do.
        assertThat(COMPOSE)
                .withFailMessage("docker-compose.prod.yml was not found at %s — this test reads the repository's "
                        + "own copy, so it must run from the module root", COMPOSE.toAbsolutePath())
                .isRegularFile();
        var root = (Map<String, Object>) new Yaml().load(Files.readString(COMPOSE, StandardCharsets.UTF_8));
        return (Map<String, Object>) root.get("services");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> appService() throws IOException {
        return (Map<String, Object>) services().get("app");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> environment(Map<String, Object> service) {
        Object env = service.get("environment");
        // Compose accepts both a mapping and a `KEY=value` list. The file uses a mapping;
        // accepting the list form means a reformat cannot turn this seal into a false pass.
        if (env instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        var out = new java.util.LinkedHashMap<String, Object>();
        if (env instanceof List<?> list) {
            for (Object item : list) {
                var s = String.valueOf(item);
                int eq = s.indexOf('=');
                if (eq > 0) {
                    out.put(s.substring(0, eq), s.substring(eq + 1));
                }
            }
        }
        return out;
    }
}
