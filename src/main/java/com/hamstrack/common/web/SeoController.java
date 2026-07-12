package com.hamstrack.common.web;

import com.hamstrack.common.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Crawler endpoints. Generated (not static files) for two reasons: absolute
 * URLs must follow app.base-url on self-hosted installs, and an instance with
 * the public landing disabled opts out of indexing entirely.
 */
@RestController
@RequiredArgsConstructor
public class SeoController {

    private static final List<String> PUBLIC_PAGES = List.of("/", "/terms", "/privacy", "/cookies");

    private final AppProperties appProperties;

    @GetMapping(value = "/robots.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    public String robots() {
        if (!appProperties.legal().publicLandingEnabled()) {
            return "User-agent: *\nDisallow: /\n";
        }
        return """
                User-agent: *
                Disallow: /w/
                Disallow: /workspaces
                Disallow: /verify-email
                Disallow: /reset-password
                Allow: /

                Sitemap: %s/sitemap.xml
                """.formatted(baseUrl());
    }

    @GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> sitemap() {
        if (!appProperties.legal().publicLandingEnabled()) {
            return ResponseEntity.notFound().build();
        }
        var urls = PUBLIC_PAGES.stream()
                .map(page -> "  <url><loc>%s%s</loc></url>\n"
                        .formatted(baseUrl(), "/".equals(page) ? "/" : page))
                .reduce("", String::concat);
        var xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                %s</urlset>
                """.formatted(urls);
        return ResponseEntity.ok(xml);
    }

    private String baseUrl() {
        var url = appProperties.baseUrl();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
