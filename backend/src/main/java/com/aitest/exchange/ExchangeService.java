package com.aitest.exchange;

import com.aitest.asset.*;
import com.aitest.common.Ids;
import com.aitest.common.JsonCodec;
import com.aitest.common.Problem;
import com.aitest.common.SecretProtector;
import com.aitest.storage.FileStorageService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class ExchangeService {
    private final AssetService assets;
    private final AssetRepository repository;
    private final FileStorageService files;
    private final JdbcTemplate jdbc;
    private final JsonCodec json;
    private final SecretProtector secrets;
    private final ExchangeParserRegistry parsers;
    private final ExchangePreflight preflight;
    private final ExchangeRedactor redactor;
    private final List<ExportCodec> exporters;

    public ExchangeService(AssetService assets, AssetRepository repository, FileStorageService files, JdbcTemplate jdbc, JsonCodec json,
                           SecretProtector secrets, ExchangeParserRegistry parsers, ExchangePreflight preflight, ExchangeRedactor redactor, List<ExportCodec> exporters) {
        this.assets = assets; this.repository = repository; this.files = files; this.jdbc = jdbc; this.json = json; this.secrets = secrets;
        this.parsers = parsers; this.preflight = preflight; this.redactor = redactor; this.exporters = exporters;
    }
    public record Pending(ExchangeBundle bundle, String parentId, Map<String, String> referenceMappings) { }
    public record ApplyResult(String importId, Map<String, String> keyToId, List<String> assetIds, int createdCount) { }
    public record Preview(String id, String projectId, AssetType type, String parentId, String format, String status, String fileId,
                          String filename, String checksum, Map<String, Object> metadata, List<ExchangeNode> nodes,
                          List<ExchangeIssue> errors, List<ExchangeIssue> warnings, ApplyResult result, Instant createdAt) { }
    public record ExportRequest(List<String> assetIds, AssetType type, String format, Map<String, Object> options) { }

    @Transactional
    public Preview preview(String projectId, AssetType type, String parentId, String requestedFormat, String filename, String mediaType, byte[] bytes,
                           Map<String, String> mappings, Map<String, String> columnMappings, Map<String, String> columnTypes) {
        repository.project(projectId);
        if (type == null) throw Problem.invalid("请选择导入资产类型");
        String format = format(requestedFormat, filename);
        if (parentId != null && parentId.isBlank()) parentId = null;
        try { ExchangeIO.checkSize(bytes, filename); }
        catch (ExchangeException e) { throw new Problem(422, "IMPORT_FILE_INVALID", "文件大小不符合导入要求", e.issue()); }
        var file = files.save(projectId, filename, mediaType == null ? "application/octet-stream" : mediaType, bytes);
        ParsedExchange parsed = parsers.parse(file.name(), format, bytes, type, columnMappings, columnTypes);
        var bundle = parsed.bundle();
        Map<String, Object> metadata = new LinkedHashMap<>(bundle.metadata()); metadata.put("checksum", file.sha256()); metadata.put("importMode", "APPEND");
        if (type != AssetType.DATASET || !Set.of("csv", "xlsx").contains(format) || !metadata.containsKey("mappingCapabilities"))
            metadata.put("mappingCapabilities", parsers.mappingCapabilities(file.name(), format, bytes, type));
        metadata.put("externalReferences", bundle.externalReferences());
        if (!columnMappings.isEmpty()) metadata.put("columnMappings", columnMappings);
        if (!columnTypes.isEmpty()) metadata.put("columnTypes", columnTypes);
        List<ExchangeNode> nodes = bundle.nodes().stream().map(node -> {
            if (node.type() != AssetType.REQUIREMENT && node.type() != AssetType.DATASET) return node;
            var data = new LinkedHashMap<>(node.data());
            if (data.get("fileId") == null || data.get("fileId").toString().isBlank()) data.put("fileId", file.id());
            return node.withData(data);
        }).toList();
        bundle = new ExchangeBundle(bundle.formatVersion(), metadata, nodes, bundle.externalReferences(), bundle.warnings());
        List<ExchangeIssue> errors = new ArrayList<>(parsed.errors());
        if (errors.isEmpty()) errors.addAll(preflight.validate(projectId, file.name(), bundle, parentId, mappings).errors());
        ExchangeBundle publicBundle = redactor.bundle(bundle);
        // Binding IDs and column declarations are structural choices, not credential values.
        var publicMetadata = new LinkedHashMap<>(publicBundle.metadata());
        publicMetadata.put("referenceMappings", mappings);
        publicMetadata.put("columnMappings", columnMappings);
        publicMetadata.put("columnTypes", columnTypes);
        String id = Ids.newId(); Instant now = Instant.now();
        jdbc.update("INSERT INTO import_job(id,project_id,asset_type,parent_id,file_id,status,parsed_rows,errors,warnings,result,created_at,format,metadata,private_payload) VALUES(?,?,?,?,?,?,?,?,?,NULL,?,?,?,?)",
                id, projectId, type.name(), parentId, file.id(), errors.isEmpty() ? "READY" : "INVALID", json.write(publicBundle.nodes()), json.write(errors), json.write(publicBundle.warnings()), Timestamp.from(now), format,
                json.write(publicMetadata), secrets.encrypt(json.write(new Pending(bundle, parentId, mappings))));
        return get(projectId, id);
    }
    public Preview get(String projectId, String id) {
        repository.project(projectId);
        var rows = jdbc.query("SELECT * FROM import_job WHERE id=? AND project_id=?", (rs, number) -> {
            var row = new org.springframework.jdbc.core.ColumnMapRowMapper().mapRow(rs, number);
            row.put("created_at", rs.getTimestamp("created_at").toInstant());
            return row;
        }, id, projectId);
        if (rows.isEmpty()) throw Problem.missing();
        var row = rows.getFirst(); var file = files.get(projectId, row.get("file_id").toString());
        List<ExchangeNode> nodes = List.of(json.read(row.get("parsed_rows").toString(), ExchangeNode[].class));
        var metadata = new LinkedHashMap<String, Object>(row.get("metadata") == null ? Map.of() : json.map(row.get("metadata").toString()));
        if (!metadata.containsKey("referenceMappings") && row.get("private_payload") != null) {
            Pending pending = json.read(secrets.decrypt(row.get("private_payload").toString()), Pending.class);
            metadata.put("referenceMappings", pending.referenceMappings());
        }
        if (!metadata.containsKey("mappingCapabilities")) {
            try { metadata.put("mappingCapabilities", parsers.mappingCapabilities(file.name(), row.get("format").toString(), Files.readAllBytes(file.path()), AssetType.valueOf(row.get("asset_type").toString()))); }
            catch (IOException missing) { metadata.put("mappingCapabilities", Map.of("renameColumns", false, "convertTypes", false)); }
        }
        List<ExchangeIssue> errors = new ArrayList<>(List.of(json.read(row.get("errors").toString(), ExchangeIssue[].class)));
        String status = row.get("status").toString();
        if ("READY".equals(status)) {
            errors.addAll(mappingErrors(file.name(), metadata));
            if (!errors.isEmpty()) status = "INVALID";
        }
        return new Preview(id, projectId, AssetType.valueOf(row.get("asset_type").toString()), (String) row.get("parent_id"), row.get("format").toString(), status, file.id(), file.name(), file.sha256(),
                metadata, nodes, List.copyOf(errors), List.of(json.read(row.get("warnings").toString(), ExchangeIssue[].class)),
                row.get("result") == null ? null : json.read(row.get("result").toString(), ApplyResult.class), (Instant) row.get("created_at"));
    }
    @Transactional
    public ApplyResult apply(String projectId, String id) {
        repository.lockProject(projectId);
        var rows = jdbc.queryForList("SELECT * FROM import_job WHERE id=? AND project_id=? FOR UPDATE", id, projectId);
        if (rows.isEmpty()) throw Problem.missing();
        var row = rows.getFirst();
        if ("APPLIED".equals(row.get("status"))) return json.read(row.get("result").toString(), ApplyResult.class);
        if (!"READY".equals(row.get("status"))) throw new Problem(422, "IMPORT_INVALID", "预览包含错误；没有资产被创建或修改", json.tree(row.get("errors").toString()));
        Pending pending = checkedPending(projectId, row);
        var file = files.get(projectId, row.get("file_id").toString());
        var checked = preflight.validate(projectId, file.name(), pending.bundle(), pending.parentId(), pending.referenceMappings());
        Map<String, String> ids = new LinkedHashMap<>();
        for (ExchangeNode node : checked.order()) {
            String parent = node.parentKey() == null || node.parentKey().isBlank() ? pending.parentId() : ids.get(node.parentKey());
            Map<String, Object> data = ExchangePreflight.resolveData(node, ids, pending.referenceMappings());
            Asset created = assets.create(projectId, node.type(), parent, node.name(), data, "IMPORT");
            ids.put(node.key(), created.id());
        }
        ApplyResult result = new ApplyResult(id, ids, List.copyOf(ids.values()), ids.size());
        jdbc.update("UPDATE import_job SET status='APPLIED',result=?,applied_at=? WHERE id=? AND project_id=?", json.write(result), Timestamp.from(Instant.now()), id, projectId);
        return result;
    }
    /** Internal parsed evidence for a versioned API comparison; never exposed as an HTTP response. */
    public Pending verifiedApiDefinitions(String projectId, String id) {
        repository.project(projectId);
        var rows = jdbc.queryForList("SELECT * FROM import_job WHERE id=? AND project_id=?", id, projectId);
        if (rows.isEmpty()) throw Problem.missing();
        var row = rows.getFirst();
        if (!"API_DEFINITION".equals(row.get("asset_type")) || !"READY".equals(row.get("status"))) throw Problem.invalid("接口对比需要尚未导入、预检通过的 API_DEFINITION 文件");
        Pending pending = checkedPending(projectId, row);
        if (pending.bundle().nodes().isEmpty() || pending.bundle().nodes().stream().anyMatch(node -> node.type() != AssetType.API_DEFINITION || node.parentKey() != null && !node.parentKey().isBlank() || !node.references().isEmpty()))
            throw Problem.invalid("接口对比只接收独立接口定义；带模块或其他测试资产的便携包请使用通用导入");
        return pending;
    }
    private Pending checkedPending(String projectId, Map<String, Object> row) {
        if (row.get("private_payload") == null) throw Problem.invalid("旧预览没有安全的私有数据，请重新上传文件");
        var file = files.get(projectId, row.get("file_id").toString());
        byte[] original;
        try {
            original = Files.readAllBytes(file.path());
            if (!file.sha256().equals(checksum(original))) throw new Problem(409, "IMPORT_FILE_CHANGED", "受管原件校验和已变化，请重新上传并预览");
        } catch (IOException e) { throw new Problem(409, "IMPORT_FILE_MISSING", "受管原件无法读取，请重新上传"); }
        var metadata = new LinkedHashMap<String, Object>(row.get("metadata") == null ? Map.of() : json.map(row.get("metadata").toString()));
        metadata.put("mappingCapabilities", parsers.mappingCapabilities(file.name(), row.get("format").toString(), original, AssetType.valueOf(row.get("asset_type").toString())));
        var mappingErrors = mappingErrors(file.name(), metadata);
        if (!mappingErrors.isEmpty()) throw new Problem(422, "IMPORT_INVALID", "此预览包含原格式不支持的转换，请重新检查后导入", mappingErrors);
        Pending pending = json.read(secrets.decrypt(row.get("private_payload").toString()), Pending.class);
        var checked = preflight.validate(projectId, file.name(), pending.bundle(), pending.parentId(), pending.referenceMappings());
        if (!checked.errors().isEmpty()) throw new Problem(422, "IMPORT_INVALID", "当前项目与预览不再匹配；没有资产被创建或修改", checked.errors());
        return pending;
    }
    public List<Map<String, Object>> capabilities() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AssetType type : AssetType.values()) {
            Set<String> formats = new java.util.TreeSet<>();
            for (var codec : exporters) if (codec.assetTypes().contains(type)) formats.addAll(codec.formats());
            result.add(Map.of("type", type, "importFormats", parsers.formats(type).stream().sorted().toList(), "exportFormats", List.copyOf(formats)));
        }
        return result;
    }
    public ExportFile export(String projectId, ExportRequest request) {
        if (request == null || request.type() == null) throw Problem.invalid("请选择导出资产类型");
        String format = format(request.format(), null);
        List<ExportCodec> matching = exporters.stream().filter(codec -> codec.assetTypes().contains(request.type()) && codec.formats().contains(format)).toList();
        if (matching.isEmpty()) throw new Problem(422, "FORMAT_NOT_AVAILABLE", "所选类型尚无此导出格式，请查看 capabilities");
        if (matching.size() > 1) throw new IllegalStateException("Duplicate exchange exporters: " + request.type() + "/" + format);
        try { return matching.getFirst().export(context(projectId, request), format); }
        catch (ExchangeException e) { throw new Problem(422, "EXPORT_INVALID", "导出内容不能用所选格式完整表示，请查看字段错误", e.issue()); }
    }
    public ExportContext context(String projectId, ExportRequest request) {
        Asset project = repository.project(projectId); List<Asset> all = repository.all(projectId, null, null);
        Map<String, Asset> byId = new LinkedHashMap<>(); Map<String, List<Asset>> children = new LinkedHashMap<>();
        for (Asset asset : all) { byId.put(asset.id(), asset); if (asset.parentId() != null) children.computeIfAbsent(asset.parentId(), ignored -> new ArrayList<>()).add(asset); }
        List<String> selected = request.assetIds() == null ? List.of() : request.assetIds();
        if (selected.size() > ExchangeIO.MAX_NODES) throw Problem.invalid("一次最多选择 20000 个资产");
        record Selection(String id, boolean descendants) { }
        ArrayDeque<Selection> pending = new ArrayDeque<>();
        if (request.type() == AssetType.PROJECT && (selected.isEmpty() || selected.equals(List.of(projectId)))) byId.keySet().forEach(id -> pending.add(new Selection(id, true)));
        else if (selected.isEmpty()) all.stream().filter(a -> a.type() == request.type()).map(Asset::id).forEach(id -> pending.add(new Selection(id, true)));
        else for (String id : selected) { if (!byId.containsKey(id)) throw Problem.missing(); pending.add(new Selection(id, true)); }
        Set<String> included = new LinkedHashSet<>(), expanded = new LinkedHashSet<>();
        while (!pending.isEmpty()) {
            Selection selection = pending.removeFirst(); String id = selection.id();
            Asset asset = byId.get(id); if (asset == null) throw new Problem(422, "BROKEN_REFERENCE", "资产引用已缺失，不能生成完整便携文件");
            if (included.add(id)) {
                if (included.size() > ExchangeIO.MAX_NODES) throw Problem.invalid("导出及其依赖超过 20000 个节点，请缩小范围");
                if (asset.parentId() != null) pending.add(new Selection(asset.parentId(), false));
                for (String field : AssetReferences.FIELDS) if (asset.data().get(field) instanceof String target && !target.isBlank()) pending.add(new Selection(target, true));
            }
            if (selection.descendants() && expanded.add(id)) for (Asset child : children.getOrDefault(id, List.of())) pending.add(new Selection(child.id(), true));
        }
        if (included.isEmpty() && request.type() != AssetType.PROJECT) throw Problem.invalid("没有可导出的资产");
        List<Asset> ordered = all.stream().filter(a -> included.contains(a.id())).toList();
        Map<String, String> keys = new LinkedHashMap<>(); int index = 1;
        for (Asset asset : ordered) keys.put(asset.id(), "node_" + index++);
        List<ExchangeNode> nodes = new ArrayList<>(); List<ExchangeIssue> warnings = new ArrayList<>(); Map<String, String> versions = new LinkedHashMap<>(); List<Asset> redactedAssets = new ArrayList<>();
        Map<String, Object> detachedSources = new LinkedHashMap<>();
        Map<String, Asset> publicAssets = new LinkedHashMap<>(); redactor.assets(ordered).forEach(a -> publicAssets.put(a.id(), a));
        for (Asset original : ordered) {
            Asset asset = publicAssets.get(original.id()); redactedAssets.add(asset); String key = keys.get(asset.id()); versions.put(key, asset.version());
            var data = new LinkedHashMap<>(asset.data()); Map<String, String> refs = new LinkedHashMap<>();
            for (String field : AssetReferences.FIELDS) if (original.data().get(field) instanceof String target && !target.isBlank()) { refs.put(field, keys.get(target)); data.remove(field); }
            for (String field : List.of("fileId", "fileIds", "attachments", "runId", "sourcePath")) if (data.containsKey(field)) {
                Object value = data.get(field);
                if (value != null && !value.toString().isEmpty() && !value.equals(List.of())) warnings.add(new ExchangeIssue("export", nodes.size() + 1, field, "文件/运行/本机路径不是便携资产，已移除；需要时请重新绑定当前项目资源"));
                data.put(field, value instanceof List<?> ? List.of() : "");
            }
            // Source snapshots and impact reports are project-owned evidence, not portable asset IDs.
            // Keep the redacted reference material in metadata without granting it trust after import.
            Map<String, Object> detached = new LinkedHashMap<>();
            for (String field : List.of("sourceSnapshotId", "impactId")) if (data.get(field) instanceof String value && !value.isBlank()) {
                detached.put(field, value); data.put(field, "");
                warnings.add(new ExchangeIssue("export", nodes.size() + 1, field, "固定源码/影响报告属于原项目，引用已移到 metadata.detachedSourceEvidence；导入后请重新分析并绑定目标项目证据"));
            }
            if (data.get("generationEvidence") instanceof Map<?, ?> evidence && !evidence.isEmpty()) {
                Map<String, Object> archived = new LinkedHashMap<>();
                evidence.forEach((field, value) -> { if (!"seal".equals(field)) archived.put(field.toString(), value); });
                detached.put("generationEvidence", archived); data.put("generationEvidence", Map.of());
                warnings.add(new ExchangeIssue("export", nodes.size() + 1, "generationEvidence", "原生成依据仅归档为参考信息，不作为导入后已验证的事实；服务器签章已移除"));
            }
            if (!detached.isEmpty()) detachedSources.put(key, detached);
            String activation = asset.type() == AssetType.WEBHOOK ? "enabled" : asset.type() == AssetType.TEST_PLAN ? "scheduleEnabled" : null;
            if (activation != null && Boolean.TRUE.equals(data.get(activation))) {
                data.put(activation, false);
                warnings.add(new ExchangeIssue("export", nodes.size() + 1, activation, "导出保留配置但关闭自动触发；导入后核对目标环境与凭据并人工启用"));
            }
            if (!json.write(original.data()).equals(json.write(asset.data()))) warnings.add(new ExchangeIssue("export", nodes.size() + 1, "credentials", "凭证已脱敏，导入后请重新配置"));
            for (var field : asset.type().fields()) if (field.kind().equals("password")) data.put(field.key(), "");
            if (asset.type() == AssetType.DATABASE_SOURCE) data.put("username", "${DB_USERNAME}");
            nodes.add(new ExchangeNode(key, asset.type(), asset.parentId() == null ? null : keys.get(asset.parentId()), asset.name(), asset.position(), data, refs));
        }
        Map<String, Object> metadata = new LinkedHashMap<>(); metadata.put("project", Map.of("name", project.name(), "data", project.data())); metadata.put("sourceVersions", versions);
        if (!detachedSources.isEmpty()) metadata.put("detachedSourceEvidence", detachedSources);
        metadata.put("exportedAt", Instant.now().toString()); metadata.put("importMode", "APPEND");
        var bundle = new ExchangeBundle(ExchangeBundle.VERSION, metadata, nodes, Map.of(), warnings);
        return new ExportContext(projectId, request.type(), List.copyOf(selected), redactedAssets, bundle, request.options() == null ? Map.of() : request.options());
    }
    private static String format(String format, String name) {
        String value = format == null || format.isBlank() ? name == null ? "json" : name.substring(name.lastIndexOf('.') + 1) : format;
        value = value.toLowerCase(Locale.ROOT).strip();
        if (value.equals("yml")) value = "yaml";
        if (!value.matches("[a-z][a-z0-9-]{0,31}")) throw Problem.invalid("格式名称无效");
        return value;
    }
    private static List<ExchangeIssue> mappingErrors(String source, Map<String, Object> metadata) {
        Map<?, ?> capabilities = metadata.get("mappingCapabilities") instanceof Map<?, ?> map ? map : Map.of();
        List<ExchangeIssue> errors = new ArrayList<>();
        if (metadata.get("columnMappings") instanceof Map<?, ?> choices && !choices.isEmpty() && !Boolean.TRUE.equals(capabilities.get("renameColumns")))
            errors.add(new ExchangeIssue(source, 1, "columnMappings", "此格式不支持列映射，请清除旧选项并重新检查"));
        if (metadata.get("columnTypes") instanceof Map<?, ?> choices && !choices.isEmpty() && !Boolean.TRUE.equals(capabilities.get("convertTypes")))
            errors.add(new ExchangeIssue(source, 1, "columnTypes", "此格式保留原始类型，不支持类型转换，请清除旧选项并重新检查"));
        return errors;
    }
    private static String checksum(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
}
