package com.hamstrack.common.config;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.junit.jupiter.api.Test;
import org.springframework.boot.tomcat.TomcatWebServerFactory;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>The gap between two reads of a request body is ours and not the connector's</strong>
 * (HD-182 review).
 *
 * <p>Tomcat's {@code disableUploadTimeout} is {@code true} out of the box, and that does NOT mean
 * the body is read without a timeout: with the flag on, {@code Http11Processor} leaves the socket
 * timeout where {@code Http11InputBuffer} set it for the request line — at the connector's
 * {@code connectionTimeout}, 60 s by default and whatever an operator sets
 * {@code server.tomcat.connection-timeout} to. So the customizer is a TIGHTENING (60 s to 20 s)
 * and a PINNING (independent of that dial), not a bound where there was none — and a trickled body
 * is finite because of the permit watchdog, never because of this.
 *
 * <p>It is asserted rather than trusted because <strong>a default is not a decision</strong>: this
 * is a switch nobody looks at, Boot exposes no property for it, and deleting the customizer would
 * silently return the body's ceiling to a number chosen for idle keep-alive connections while
 * passing every other test in this repository.
 */
class TomcatUploadTimeoutCustomizerTest {

    @Test
    void theUploadTimeoutIsOnAndTomcatsDefaultIsOff() {
        var factory = new TomcatServletWebServerFactory();
        var connector = new Connector(TomcatWebServerFactory.DEFAULT_PROTOCOL);
        var protocol = (AbstractHttp11Protocol<?>) connector.getProtocolHandler();

        assertThat(protocol.getDisableUploadTimeout())
                .as("if Tomcat's own default ever changes, the reason written on the customizer "
                    + "changes with it — this assertion is the reason, not decoration")
                .isTrue();
        assertThat(protocol.getConnectionTimeout())
                .as("and THIS is what the body inherits while that flag is on — the connector's "
                    + "connectionTimeout, not the absence of a timeout. The customizer's whole "
                    + "effect is stated against this number, so it is read here rather than "
                    + "asserted from memory")
                .isEqualTo(60_000);

        new TomcatUploadTimeoutCustomizer().customize(factory);
        factory.getConnectorCustomizers().forEach(customizer -> customizer.customize(connector));

        assertThat(protocol.getDisableUploadTimeout())
                .as("with the upload timeout disabled, the gap between two reads of a body is "
                    + "whatever connectionTimeout happens to be — a keep-alive dial, three times "
                    + "as loose by default, and free for an operator to raise for reasons that "
                    + "have nothing to do with bodies")
                .isFalse();
        assertThat(protocol.getConnectionUploadTimeout())
                .as("and the timeout has to be a real number of milliseconds, since it is a socket "
                    + "READ bound (the gap between two reads) rather than a deadline on the upload")
                .isEqualTo(TomcatUploadTimeoutCustomizer.CONNECTION_UPLOAD_TIMEOUT_MS);
    }
}
