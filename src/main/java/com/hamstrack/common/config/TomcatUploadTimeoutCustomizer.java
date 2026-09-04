package com.hamstrack.common.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.springframework.boot.tomcat.ConfigurableTomcatWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

/**
 * <strong>The gap between two reads of a request body is pinned at 20 s</strong> (HD-182 review) —
 * Tomcat's own upload timeout, switched on, because Boot exposes no property for it.
 *
 * <h2>What this actually changes, measured rather than assumed</h2>
 *
 * <p>Tomcat ships {@code disableUploadTimeout=true}, and the tempting reading of that name — "the
 * body is then read with no timeout at all" — is <strong>false</strong>. With the flag ON,
 * {@code Http11Processor.service} never touches the socket timeout for the body, so the body
 * inherits the one {@code Http11InputBuffer} already set for the request line and headers: the
 * connector's {@code connectionTimeout} ({@code server.tomcat.connection-timeout}, 60 s by
 * default). Measured twice — in {@code tomcat-embed-core} 11.0.22's sources, where the upload
 * timeout is applied only in the {@code !disableUploadTimeout} branch and {@code connectionTimeout}
 * restored after it, and by a runtime probe on this project's classpath, which printed
 * {@code disableUploadTimeout=true, connectionTimeout (soTimeout)=60000}. With the flag OFF,
 * {@code service()} swaps in {@link #CONNECTION_UPLOAD_TIMEOUT_MS} for the duration of the body.
 *
 * <p>So what this does is <strong>tighten 60 s to 20 s and pin it</strong>: the ceiling on the gap
 * stops being whatever an operator has set {@code server.tomcat.connection-timeout} to for
 * keep-alive reasons and becomes a number chosen for bodies. It is not the difference between
 * bounded and unbounded, and nothing here or in any document may say that it is.
 *
 * <p><strong>Which means this layer does not make a trickled body finite.</strong> Against a client
 * sending one byte per interval, the shipped default and this one are both a price per packet — one
 * every 60 s, or one every 20 s — and neither bounds how long the request holds what it holds,
 * including the {@link ExpensiveReadProperties} permit taken in {@code preHandle} before the body is
 * read. <strong>The bound that makes the hold finite is the watchdog</strong>,
 * {@code PerPrincipalInFlightLimit.sweepStalePermits}, which takes a permit back at a fixed age and
 * counts it. This layer triples the packet rate an attacker must sustain, and it ships INSIDE the
 * application, so every deployment has it — including a self-hosted install behind somebody's
 * nginx, Traefik or cloud load balancer, none of which we can see. The bundled {@code Caddyfile}'s
 * {@code read_body} is an absolute deadline and covers our own edge only.
 *
 * <h2>What the number means, which is not what it looks like</h2>
 *
 * <p>{@code connectionUploadTimeout} is a SOCKET READ timeout applied while the body is read, not
 * a deadline on the upload. It bounds the gap between two reads, so a genuinely slow client
 * uploading a 25 MB attachment over a poor link is unaffected as long as its bytes keep arriving —
 * which is why a value this tight is safe here and why the equivalent at the edge (Caddy's
 * {@code read_body}, an absolute deadline) has to be far looser.
 *
 * <p>Not exposed as a property on purpose. It is not a per-install trade-off — no deployment wants
 * a 20-second gap between packets — and a dial invites somebody to raise it to "fix" an upload
 * problem whose cause is elsewhere. {@code SseRegistry.EMITTER_TIMEOUT_MS} is the precedent.
 *
 * <p><strong>Responses are not bounded here and cannot be.</strong> A slow reader holds a permit
 * the same way; the mirror instrument would be a write timeout, and this application serves SSE
 * streams that live for up to 30 minutes, so any write deadline short enough to matter would cut
 * them. The watchdog is what bounds that half as well.
 */
@Component
@Slf4j
public class TomcatUploadTimeoutCustomizer
        implements WebServerFactoryCustomizer<ConfigurableTomcatWebServerFactory> {

    /**
     * 20 s between two reads of a request body. Generous against any working connection — TCP on a
     * live link delivers something long before this — and a third of the 60 s the body would
     * otherwise inherit from {@code connectionTimeout}, so a trickling client has to sustain three
     * times the packet rate for the same hold. It buys a rate, not an end; the watchdog is the end.
     */
    static final int CONNECTION_UPLOAD_TIMEOUT_MS = 20_000;

    @Override
    public void customize(ConfigurableTomcatWebServerFactory factory) {
        factory.addConnectorCustomizers(connector -> {
            if (connector.getProtocolHandler() instanceof AbstractHttp11Protocol<?> http11) {
                http11.setDisableUploadTimeout(false);
                http11.setConnectionUploadTimeout(CONNECTION_UPLOAD_TIMEOUT_MS);
                log.debug("Tomcat upload timeout enabled: {} ms between two reads of a "
                          + "request body, in place of the {} ms it would otherwise inherit from "
                          + "connectionTimeout", CONNECTION_UPLOAD_TIMEOUT_MS,
                          http11.getConnectionTimeout());
            }
        });
    }
}
