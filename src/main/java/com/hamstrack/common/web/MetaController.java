package com.hamstrack.common.web;

import com.hamstrack.common.config.AppProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public (unauthenticated) instance metadata for the SPA: which optional
 * surfaces are enabled on this installation, and the application version.
 * DC operators toggle the flags via app.legal.* / app.registration.* properties.
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

    public record MetaResponse(
            boolean publicLandingEnabled,
            boolean termsAcceptanceRequired,
            boolean publicSignupEnabled,
            String version
    ) {}

    @GetMapping("/api/meta")
    public MetaResponse meta() {
        return new MetaResponse(
                appProperties.legal().publicLandingEnabled(),
                appProperties.legal().termsAcceptanceRequired(),
                appProperties.registration().publicSignupEnabled(),
                version
        );
    }
}
