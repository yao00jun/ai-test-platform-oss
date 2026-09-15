package com.aitest.exchange;

import com.aitest.asset.AssetType;
import com.aitest.common.Problem;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class ExchangeController {
    private final ExchangeService exchange;
    public ExchangeController(ExchangeService exchange) { this.exchange = exchange; }
    @GetMapping("/api/exchange/capabilities") Object capabilities() { return exchange.capabilities(); }
    @PostMapping("/api/projects/{projectId}/imports/preview")
    ExchangeService.Preview preview(@PathVariable String projectId, @RequestParam MultipartFile file, @RequestParam AssetType type,
                                   @RequestParam(required = false) String parentId, @RequestParam(required = false) String format,
                                   @RequestParam(defaultValue = "{}") String referenceMappings, @RequestParam(defaultValue = "{}") String columnMappings,
                                   @RequestParam(defaultValue = "{}") String columnTypes) throws IOException {
        return exchange.preview(projectId, type, parentId, format, file.getOriginalFilename() == null ? "import.json" : file.getOriginalFilename(), file.getContentType(), file.getBytes(),
                mapping(referenceMappings, "referenceMappings"), mapping(columnMappings, "columnMappings"), mapping(columnTypes, "columnTypes"));
    }
    @GetMapping("/api/projects/{projectId}/imports/{id}") ExchangeService.Preview get(@PathVariable String projectId, @PathVariable String id) { return exchange.get(projectId, id); }
    @PostMapping("/api/projects/{projectId}/imports/{id}/apply") ExchangeService.ApplyResult apply(@PathVariable String projectId, @PathVariable String id, @RequestBody(required = false) Map<String, Object> options) {
        if (options != null && !options.isEmpty() && !(options.size() == 1 && "APPEND".equals(options.get("mode")))) throw Problem.invalid("当前只支持 APPEND 原子导入；不会覆盖已有记录");
        return exchange.apply(projectId, id);
    }
    @PostMapping("/api/projects/{projectId}/exports") ResponseEntity<byte[]> export(@PathVariable String projectId, @RequestBody ExchangeService.ExportRequest request) { return download(exchange.export(projectId, request)); }
    public static ResponseEntity<byte[]> download(ExportFile file) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.mediaType())).contentLength(file.bytes().length)
                .header("X-Content-Type-Options", "nosniff").header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.filename(), StandardCharsets.UTF_8).build().toString()).body(file.bytes());
    }
    private static Map<String, String> mapping(String text, String field) {
        try {
            Map<String, Object> values = ExchangeIO.map(ExchangeIO.json(text, field), field, 1, field); Map<String, String> result = new LinkedHashMap<>();
            values.forEach((key, value) -> { if (!(value instanceof String string) || string.isBlank()) throw Problem.invalid(field + " 必须是非空字符串映射"); result.put(key, (String) value); });
            return result;
        } catch (ExchangeException e) { throw new Problem(422, "IMPORT_OPTIONS_INVALID", "映射必须为 JSON 对象", e.issue()); }
    }
}
