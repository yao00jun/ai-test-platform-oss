package com.aitest.asset;

import com.aitest.common.Ids;
import com.aitest.common.JsonCodec;
import com.aitest.common.Problem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Repository
public class AssetRepository {
    private final JdbcTemplate jdbc;
    private final JsonCodec json;
    private final AssetSecrets secrets;
    public AssetRepository(JdbcTemplate jdbc, JsonCodec json, AssetSecrets secrets) { this.jdbc = jdbc; this.json = json; this.secrets = secrets; }

    public Asset project(String id) {
        List<Asset> rows = jdbc.query("SELECT * FROM project WHERE id=? AND deleted=FALSE", (rs, n) -> projectRow(rs), id);
        if (rows.isEmpty()) throw Problem.missing();
        return rows.getFirst();
    }
    public List<Asset> projects() { return jdbc.query("SELECT * FROM project WHERE deleted=FALSE ORDER BY position,created_at,id", (rs, n) -> projectRow(rs)); }
    private Asset projectRow(ResultSet rs) throws SQLException {
        Map<String, Object> data = new LinkedHashMap<>();
        for (FieldDefinition field : AssetType.PROJECT.fields()) data.put(field.key(), rs.getString(field.column()) == null ? "" : rs.getString(field.column()));
        return new Asset(rs.getString("id"), rs.getString("id"), AssetType.PROJECT, null, rs.getString("name"), rs.getString("version"), rs.getInt("position"), rs.getString("source"), rs.getBoolean("confirmed"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(), data);
    }
    public void lockProject(String projectId) {
        List<String> ids = jdbc.queryForList("SELECT id FROM project WHERE id=? AND deleted=FALSE FOR UPDATE", String.class, projectId);
        if (ids.isEmpty()) throw Problem.missing();
    }
    public Asset find(String projectId, String id) {
        if (projectId.equals(id)) return project(id);
        List<Asset> rows = jdbc.query("SELECT * FROM asset WHERE id=? AND project_id=? AND deleted=FALSE", (rs, n) -> metadata(rs), id, projectId);
        if (rows.isEmpty()) throw Problem.missing();
        return loadData(rows.getFirst());
    }
    public AssetPage list(String projectId, AssetType type, String parentId, String query, int offset, int limit) {
        List<Object> args = new ArrayList<>(List.of(projectId));
        String where = "project_id=? AND deleted=FALSE";
        if (type != null) { where += " AND asset_type=?"; args.add(type.name()); }
        if (parentId != null) {
            if (parentId.equals("ROOT")) where += " AND parent_id IS NULL";
            else { where += " AND parent_id=?"; args.add(parentId); }
        }
        if (query != null && !query.isBlank()) { where += " AND name LIKE ?"; args.add("%" + query + "%"); }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM asset WHERE " + where, Long.class, args.toArray());
        args.add(limit); args.add(offset);
        List<Asset> rows = jdbc.query("SELECT * FROM asset WHERE " + where + " ORDER BY position,created_at,id LIMIT ? OFFSET ?", (rs, n) -> metadata(rs), args.toArray());
        return new AssetPage(loadData(rows), total == null ? 0 : total);
    }
    public List<Asset> recent(String projectId, AssetType type, int limit) {
        return loadData(jdbc.query("SELECT * FROM asset WHERE project_id=? AND asset_type=? AND deleted=FALSE ORDER BY updated_at DESC,id DESC LIMIT ?",
                (rs, n) -> metadata(rs), projectId, type.name(), limit));
    }
    private Asset metadata(ResultSet rs) throws SQLException {
        return new Asset(rs.getString("id"), rs.getString("project_id"), AssetType.valueOf(rs.getString("asset_type")), rs.getString("parent_id"), rs.getString("name"), rs.getString("version"), rs.getInt("position"), rs.getString("source"), rs.getBoolean("confirmed"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(), Map.of());
    }
    public List<Asset> all(String projectId, AssetType type, String parentId) {
        List<Asset> result = new ArrayList<>();
        int offset = 0;
        while (true) {
            AssetPage page = list(projectId, type, parentId, "", offset, 1000);
            result.addAll(page.items()); offset += page.items().size();
            if (offset >= page.total() || page.items().isEmpty()) return result;
        }
    }
    private Asset loadData(Asset meta) {
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM " + meta.type().table() + " WHERE asset_id=?", meta.id());
        return loadData(meta, row);
    }
    private List<Asset> loadData(List<Asset> metadata) {
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        metadata.stream().collect(Collectors.groupingBy(Asset::type)).forEach((type, entries) -> {
            for (int start = 0; start < entries.size(); start += 1000) {
                var group = entries.subList(start, Math.min(start + 1000, entries.size()));
                String marks = String.join(",", java.util.Collections.nCopies(group.size(), "?"));
                jdbc.queryForList("SELECT * FROM " + type.table() + " WHERE asset_id IN (" + marks + ")", group.stream().map(Asset::id).toArray())
                        .forEach(row -> byId.put(row.get("asset_id").toString(), row));
            }
        });
        return metadata.stream().map(meta -> loadData(meta, byId.get(meta.id()))).toList();
    }
    private Asset loadData(Asset meta, Map<String, Object> row) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (FieldDefinition field : meta.type().fields()) {
            Object value = row.get(field.column());
            if (value != null && field.structured()) value = json.tree(value.toString());
            if (value instanceof Number n && field.kind().equals("boolean")) value = n.intValue() != 0;
            data.put(field.key(), value);
        }
        return AssetSecrets.withData(meta, secrets.decode(meta.type(), data));
    }
    public void insertProject(Asset asset) {
        String fields = AssetType.PROJECT.fields().stream().map(field -> "`" + field.column() + "`").collect(Collectors.joining(","));
        List<Object> args = new ArrayList<>(List.of(asset.id(), asset.name(), Long.parseLong(asset.version()), asset.position(), asset.source(), asset.confirmed(), Timestamp.from(asset.createdAt()), Timestamp.from(asset.updatedAt())));
        args.addAll(values(AssetType.PROJECT, asset.data()));
        jdbc.update("INSERT INTO project(id,name,version,position,source,confirmed,created_at,updated_at," + fields + ") VALUES(" + String.join(",", java.util.Collections.nCopies(args.size(), "?")) + ")", args.toArray());
    }
    public int nextPosition(String projectId, AssetType type, String parentId) {
        Integer result = jdbc.queryForObject("SELECT COALESCE(MAX(position),-1)+1 FROM asset WHERE project_id=? AND asset_type=? AND parent_id <=> ? AND deleted=FALSE", Integer.class, projectId, type.name(), parentId);
        return result == null ? 0 : result;
    }
    public void insert(Asset asset) {
        jdbc.update("INSERT INTO asset(id,project_id,asset_type,parent_id,name,version,position,source,confirmed,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)", asset.id(), asset.projectId(), asset.type().name(), asset.parentId(), asset.name(), Long.parseLong(asset.version()), asset.position(), asset.source(), asset.confirmed(), Timestamp.from(asset.createdAt()), Timestamp.from(asset.updatedAt()));
        List<FieldDefinition> fields = asset.type().fields();
        String columns = fields.stream().map(f -> "`" + f.column() + "`").collect(Collectors.joining(","));
        String marks = fields.stream().map(f -> "?").collect(Collectors.joining(","));
        List<Object> args = new ArrayList<>(List.of(asset.id())); args.addAll(values(asset.type(), asset.data()));
        jdbc.update("INSERT INTO " + asset.type().table() + "(asset_id," + columns + ") VALUES(?," + marks + ")", args.toArray());
    }
    public void insertBatch(List<Asset> assets) {
        // Preserve topological order for the self-referencing parent FK. Domain
        // rows can be grouped only after every metadata row has been inserted.
        jdbc.batchUpdate("INSERT INTO asset(id,project_id,asset_type,parent_id,name,version,position,source,confirmed,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                assets, 250, (statement, asset) -> bind(statement, asset.id(), asset.projectId(), asset.type().name(), asset.parentId(), asset.name(),
                        Long.parseLong(asset.version()), asset.position(), asset.source(), asset.confirmed(), Timestamp.from(asset.createdAt()), Timestamp.from(asset.updatedAt())));
        var byType = assets.stream().collect(Collectors.groupingBy(Asset::type, LinkedHashMap::new, Collectors.toList()));
        byType.forEach((type, entries) -> {
            String columns = type.fields().stream().map(field -> "`" + field.column() + "`").collect(Collectors.joining(","));
            String marks = String.join(",", java.util.Collections.nCopies(type.fields().size() + 1, "?"));
            jdbc.batchUpdate("INSERT INTO " + type.table() + "(asset_id," + columns + ") VALUES(" + marks + ")", entries, 250, (statement, asset) -> {
                var args = new ArrayList<Object>(); args.add(asset.id()); args.addAll(values(type, asset.data())); bind(statement, args.toArray());
            });
        });
    }
    public void reviseBatch(List<Asset> assets, String operation, String actor) {
        jdbc.batchUpdate("INSERT INTO asset_revision(id,project_id,asset_id,version,operation,source,snapshot,created_at) VALUES(?,?,?,?,?,?,?,?)",
                assets, 250, (statement, asset) -> {
                    Asset stored = AssetSecrets.withData(asset, secrets.encode(asset.type(), asset.data()));
                    bind(statement, Ids.newId(), asset.projectId(), asset.id(), Long.parseLong(asset.version()), operation, actor, json.write(stored), Timestamp.from(Instant.now()));
                });
        jdbc.batchUpdate("INSERT INTO audit_event(project_id,asset_id,action,source,detail,created_at) VALUES(?,?,?,?,?,?)",
                assets, 250, (statement, asset) -> bind(statement, asset.projectId(), asset.id(), operation, actor,
                        json.write(Map.of("version", asset.version(), "type", asset.type().name())), Timestamp.from(Instant.now())));
    }
    private static void bind(PreparedStatement statement, Object... values) throws SQLException {
        for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
    }
    public void update(Asset changed, String baseVersion) {
        int affected;
        if (changed.type() == AssetType.PROJECT) {
            String assignments = AssetType.PROJECT.fields().stream().map(field -> "`" + field.column() + "`=?").collect(Collectors.joining(","));
            List<Object> args = new ArrayList<>(List.of(changed.name(), changed.confirmed(), Timestamp.from(changed.updatedAt())));
            args.addAll(values(AssetType.PROJECT, changed.data())); args.add(changed.id()); args.add(Long.parseLong(baseVersion));
            affected = jdbc.update("UPDATE project SET name=?,confirmed=?,version=version+1,updated_at=?," + assignments + " WHERE id=? AND version=? AND deleted=FALSE", args.toArray());
        } else {
            affected = jdbc.update("UPDATE asset SET name=?,confirmed=?,version=version+1,updated_at=? WHERE id=? AND project_id=? AND version=? AND deleted=FALSE", changed.name(), changed.confirmed(), Timestamp.from(changed.updatedAt()), changed.id(), changed.projectId(), Long.parseLong(baseVersion));
            if (affected == 1) {
                String assignments = changed.type().fields().stream().map(f -> "`" + f.column() + "`=?").collect(Collectors.joining(","));
                List<Object> args = new ArrayList<>(values(changed.type(), changed.data())); args.add(changed.id());
                jdbc.update("UPDATE " + changed.type().table() + " SET " + assignments + " WHERE asset_id=?", args.toArray());
            }
        }
        if (affected != 1) throw Problem.conflict("资产已被修改或删除，请重新载入后重试");
    }
    private List<Object> values(AssetType type, Map<String, Object> data) {
        Map<String, Object> encoded = secrets.encode(type, data);
        List<Object> values = new ArrayList<>();
        for (FieldDefinition f : type.fields()) {
            Object value = encoded.get(f.key());
            values.add(value != null && f.structured() ? json.write(value) : value);
        }
        return values;
    }
    public void revise(Asset asset, String operation, String actor) {
        Asset stored = AssetSecrets.withData(asset, secrets.encode(asset.type(), asset.data()));
        jdbc.update("INSERT INTO asset_revision(id,project_id,asset_id,version,operation,source,snapshot,created_at) VALUES(?,?,?,?,?,?,?,?)", Ids.newId(), asset.projectId(), asset.id(), Long.parseLong(asset.version()), operation, actor, json.write(stored), Timestamp.from(Instant.now()));
        jdbc.update("INSERT INTO audit_event(project_id,asset_id,action,source,detail,created_at) VALUES(?,?,?,?,?,?)", asset.projectId(), asset.id(), operation, actor, json.write(Map.of("version", asset.version(), "type", asset.type().name())), Timestamp.from(Instant.now()));
    }
    public List<Revision> history(String projectId, String id) {
        return jdbc.query("SELECT * FROM asset_revision WHERE project_id=? AND asset_id=? ORDER BY version DESC", (rs, n) -> {
            Asset stored = json.read(rs.getString("snapshot"), Asset.class);
            Asset decoded = AssetSecrets.withData(stored, secrets.decode(stored.type(), stored.data()));
            return new Revision(rs.getString("id"), id, rs.getString("version"), rs.getString("operation"), rs.getString("source"), rs.getTimestamp("created_at").toInstant(), decoded);
        }, projectId, id);
    }
    public JdbcTemplate jdbc() { return jdbc; }
}
