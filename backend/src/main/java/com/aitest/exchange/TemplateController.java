package com.aitest.exchange;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/templates")
public class TemplateController {
    private final TemplateCatalog templates;
    public TemplateController(TemplateCatalog templates) { this.templates = templates; }
    @GetMapping List<TemplateCatalog.Family> list() { return templates.list(); }
    @GetMapping("/{family}") ResponseEntity<byte[]> download(@PathVariable String family, @RequestParam(required = false) String format) { return ExchangeController.download(templates.download(family, format)); }
}
