package com.aitest.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Vue history routes in the single-JAR distribution; API/static misses keep their 404. */
@Controller
public final class SpaController {
    @GetMapping({"/projects", "/cases", "/api-tests", "/scenarios", "/ui-tests", "/plans", "/bugs"})
    public String index() { return "forward:/index.html"; }
}
