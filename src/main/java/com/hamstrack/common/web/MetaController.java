package com.hamstrack.common.web;

import com.hamstrack.common.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public (unauthenticated) instance metadata for the SPA: which optional
 * surfaces are enabled on this installation. DC operators toggle these via
 * app.legal.* / app.registration.* properties.
 */
@RestController
@RequiredArgsConstructor
public class MetaController {

    private final AppProperties appProperties;

    public record MetaResponse(
            boolean publicLandingEnabled,
            boolean termsAcceptanceRequired,
            boolean publicSignupEnabled
    ) {}

    @GetMapping("/api/meta")
    public MetaResponse meta() {
        return new MetaResponse(
                appProperties.legal().publicLandingEnabled(),
                appProperties.legal().termsAcceptanceRequired(),
                appProperties.registration().publicSignupEnabled()
        );
    }
}
