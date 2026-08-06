package com.hamstrack.common.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * SPA fallback: forwards all non-API, non-static-file paths to index.html so
 * client-side routes (React Router) survive a full page load / deep link.
 * API routes win by mapping specificity; asset paths are excluded by pattern.
 */
@Controller
public class SpaController {

    // Single-segment paths: /login, /register, /workspaces
    // Excludes dot-containing paths, the /assets static resource directory, and
    // /actuator (the management endpoints live on the separate management port —
    // on the app port they must 404, never fall through to the SPA and return a
    // misleading 200 HTML page to a metrics scraper hitting the wrong port).
    @GetMapping("/{path:^(?!assets$|actuator$)[^\\.]*}")
    public String spaRoot() {
        return "forward:/index.html";
    }

    // Multi-segment paths: /w/xxx, /w/xxx/p/yyy — excludes /api/** because
    // Spring MVC always prefers the more specific @RestController matches first.
    // Also excludes /assets/** and /actuator/** (see spaRoot) so those are not
    // swallowed by the SPA fallback.
    @GetMapping("/{path:^(?!assets$|actuator$)[^\\.]*}/**")
    public String spaNested() {
        return "forward:/index.html";
    }
}
