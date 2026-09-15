package com.aitest.asset;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AssetController {
    private final AssetService assets;
    public AssetController(AssetService assets) { this.assets = assets; }
    @GetMapping("/catalog") Object catalog() {
        return Map.of("types", Arrays.stream(AssetType.values()).map(t -> Map.of("type", t, "label", t.label(), "fields", t.fields(), "childTypes", t.childTypes(), "formats", t.formats())).toList());
    }
    @GetMapping("/projects") List<Asset> projects() { return assets.projects(); }
    @PostMapping("/projects") @ResponseStatus(HttpStatus.CREATED)
    Asset createProject(@RequestBody ProjectInput input) { return assets.createProject(input.name(), input.data() == null ? Map.of() : input.data()); }
    @GetMapping("/projects/{projectId}/assets")
    AssetPage list(@PathVariable String projectId, @RequestParam(required = false) AssetType type, @RequestParam(required = false) String parentId, @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "100") int limit) { return assets.list(projectId, type, parentId, q, offset, limit); }
    @GetMapping("/projects/{projectId}/assets/{id}") Asset get(@PathVariable String projectId, @PathVariable String id) { return assets.get(projectId, id); }
    @PostMapping("/projects/{projectId}/assets") @ResponseStatus(HttpStatus.CREATED)
    Asset create(@PathVariable String projectId, @RequestBody CreateInput input) { return assets.create(projectId, input.type(), input.parentId(), input.name(), input.data() == null ? Map.of() : input.data(), "MANUAL"); }
    @PatchMapping("/projects/{projectId}/assets/{id}")
    Asset update(@PathVariable String projectId, @PathVariable String id, @RequestBody UpdateInput input) { return assets.update(projectId, id, input.baseVersion(), input.name(), input.data(), input.confirmed(), "MANUAL"); }
    @DeleteMapping("/projects/{projectId}/assets/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String projectId, @PathVariable String id, @RequestParam String baseVersion) { assets.delete(projectId, id, baseVersion); }
    @GetMapping("/projects/{projectId}/assets/{id}/children") List<Asset> children(@PathVariable String projectId, @PathVariable String id) { return assets.children(projectId, id); }
    @GetMapping("/projects/{projectId}/assets/{id}/history") List<Revision> history(@PathVariable String projectId, @PathVariable String id) { return assets.history(projectId, id); }
    @PostMapping("/projects/{projectId}/assets/{id}/undo")
    Asset undo(@PathVariable String projectId, @PathVariable String id, @RequestBody UndoInput input) { return assets.undo(projectId, id, input.baseVersion(), input.revisionId()); }
    @PostMapping("/projects/{projectId}/assets/reorder")
    List<Asset> reorder(@PathVariable String projectId, @RequestBody ReorderInput input) { return assets.reorder(projectId, input.type(), input.parentId(), input.items()); }
    public record ProjectInput(String name, Map<String, Object> data) { }
    public record CreateInput(AssetType type, String parentId, String name, Map<String, Object> data) { }
    public record UpdateInput(String baseVersion, String name, Map<String, Object> data, Boolean confirmed) { }
    public record UndoInput(String baseVersion, String revisionId) { }
    public record ReorderInput(AssetType type, String parentId, List<AssetService.OrderItem> items) { }
}
