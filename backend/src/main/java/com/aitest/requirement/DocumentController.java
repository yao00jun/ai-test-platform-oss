package com.aitest.requirement;

import com.aitest.asset.Asset;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;

@RestController
@RequestMapping("/api/projects/{projectId}/documents")
public class DocumentController {
    private final DocumentIngestionService documents;
    public DocumentController(DocumentIngestionService documents) { this.documents = documents; }
    @PostMapping Asset upload(@PathVariable String projectId, @RequestParam MultipartFile file, @RequestParam(required = false) String idempotencyKey) throws IOException { return documents.ingest(projectId, file.getOriginalFilename() == null ? "document.md" : file.getOriginalFilename(), file.getBytes(), "", idempotencyKey); }
    @PostMapping("/read") Asset read(@PathVariable String projectId, @RequestBody ReadInput input) { return documents.read(projectId, input.path(), input.text(), input.name(), input.idempotencyKey()); }
    public record ReadInput(String path, String text, String name, String idempotencyKey) { }
}
