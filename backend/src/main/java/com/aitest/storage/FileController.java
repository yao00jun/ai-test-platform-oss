package com.aitest.storage;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/projects/{projectId}/files")
public class FileController {
    private final FileStorageService files;
    public FileController(FileStorageService files) { this.files = files; }
    @PostMapping Object upload(@PathVariable String projectId, @RequestParam MultipartFile file) throws IOException {
        var stored = files.save(projectId, file.getOriginalFilename(), file.getContentType() == null ? "application/octet-stream" : file.getContentType(), file.getBytes());
        return Map.of("id", stored.id(), "name", stored.name(), "size", stored.size());
    }
    @GetMapping("/{id}") ResponseEntity<FileSystemResource> download(@PathVariable String projectId, @PathVariable String id) {
        var file = files.get(projectId, id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.mediaType()))
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.name(), StandardCharsets.UTF_8).build().toString())
                .contentLength(file.size()).body(new FileSystemResource(file.path()));
    }
}
