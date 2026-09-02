package com.hamstrack.common.web;

import com.hamstrack.common.config.AppProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public (unauthenticated) instance metadata for the SPA: which optional
 * surfaces are enabled on this installation, and the application version.
 * DC operators toggle the flags via app.legal.* / app.registration.* properties, and
 * set the privacy contact address via app.privacy.contact-email.
 *
 * <p>Deliberately NOT annotated {@code @Validated} (ADR-0018). On a bean Spring MVC
 * dispatches to, that annotation makes {@code HandlerMethod.shouldValidateArguments()}
 * return false, so MVC skips its own method validation and any declared constraint here
 * would refuse through a different mechanism than every sibling controller's.
 *
 * <p><strong>ADR-0018 is about the kind of bean, not about the annotation.</strong> A
 * {@code @ConfigurationProperties} class is the other kind: it is bound once at startup and
 * dispatched to never, so nothing is being suppressed there and the annotation is instead the
 * only thing that makes a declared constraint fire at all. Hence the invariant to check when
 * adding a bound value — <em>a properties class that declares a constraint carries
 * {@code @Validated}</em>, and a bean MVC dispatches to never does. What that buys the values
 * read below is on {@link AppProperties}: the privacy address is constrained there, at startup,
 * because this endpoint publishes it unauthenticated and the SPA turns it into a mail link.
 * Constraining it <em>here</em> would be the forbidden move, and would also come too late.
 */
@RestController
public class MetaController {

    private final AppProperties appProperties;
    private final String version;

    public MetaController(AppProperties appProperties, ObjectProvider<BuildProperties> buildProperties) {
        this.appProperties = appProperties;
        // BuildProperties exists only when the build-info goal ran (Maven build);
        // absent when launched straight from an IDE — report "dev" then
        BuildProperties build = buildProperties.getIfAvailable();
        this.version = build != null ? build.getVersion() : "dev";
    }

    /**
     * {@code privacyContactEmail} is the address a user is told to write to for a privacy
     * request — account deletion in particular (HD-193). It is the <strong>empty
     * string</strong> when the operator has configured none, and never {@code null}: the
     * SPA's Account page renders its deletion section either way and falls back to saying
     * this installation's administrator handles the request, so the affordance is never
     * hidden by a property nobody set.
     *
     * <p>This endpoint is unauthenticated, and that is not a new exposure. On Cloud the
     * same address is already published on the privacy policy; on DC it is empty until an
     * operator decides to publish one. See {@code AppProperties.Privacy}.
     */
    public record MetaResponse(
            boolean publicLandingEnabled,
            boolean termsAcceptanceRequired,
            boolean publicSignupEnabled,
            String privacyContactEmail,
            String version
    ) {}

    @GetMapping("/api/meta")
    public MetaResponse meta() {
        return new MetaResponse(
                appProperties.legal().publicLandingEnabled(),
                appProperties.legal().termsAcceptanceRequired(),
                appProperties.registration().publicSignupEnabled(),
                appProperties.privacy().contactEmail(),
                version
        );
    }
}
