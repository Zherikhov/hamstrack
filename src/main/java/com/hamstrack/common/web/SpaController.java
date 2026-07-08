package com.hamstrack.common.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaController {

    // Single-segment paths: /login, /register, /workspaces
    // Excludes dot-containing paths and the /assets static resource directory.
    @GetMapping("/{path:^(?!assets$)[^\\.]*}")
    public String spaRoot() {
        return "forward:/index.html";
    }

    // Multi-segment paths: /w/xxx, /w/xxx/p/yyy — excludes /api/** because
    // Spring MVC always prefers the more specific @RestController matches first.
    // Also excludes /assets/** so static resources are served by Spring's resource handler.
    @GetMapping("/{path:^(?!assets$)[^\\.]*}/**")
    public String spaNested() {
        return "forward:/index.html";
    }
}
