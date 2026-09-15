package com.aitest.ai;

import com.aitest.asset.AssetRepository;
import com.aitest.common.Ids;
import com.aitest.common.JsonCodec;
import com.aitest.common.Problem;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class AiConversationService {
    private final JdbcTemplate jdbc;
    private final JsonCodec json;
    private final AssetRepository projects;
    public AiConversationService(JdbcTemplate jdbc, JsonCodec json, AssetRepository projects) { this.jdbc = jdbc; this.json = json; this.projects = projects; }
    public String ensure(String requestedId, String projectId, String scope, String targetId, String targetType) {
        projects.project(projectId);
        String identity = projectId + ":" + targetId + (scope.equals("GENERATE") ? ":" + targetType : "");
        String id = requestedId == null || requestedId.isBlank() ? stableId(scope, identity) : requestedId;
        if (!id.matches("[A-Za-z0-9_-]{1,64}")) throw Problem.invalid("会话 ID 格式无效");
        try {
            jdbc.update("INSERT INTO ai_conversation(id,project_id,scope,target_id,target_type,created_at) VALUES(?,?,?,?,?,?)", id, projectId, scope, targetId, targetType, Timestamp.from(Instant.now()));
        } catch (DuplicateKeyException existing) { /* The scope is checked below even for caller-selected IDs. */ }
        Map<String, Object> conversation = get(projectId, id);
        if (!scope.equals(conversation.get("scope")) || !Objects.equals(targetId, conversation.get("targetId")) || !Objects.equals(targetType, conversation.get("targetType"))) throw new Problem(409, "CONVERSATION_SCOPE_MISMATCH", "此会话属于其他目标或生成范围");
        return id;
    }
    public Map<String, Object> get(String projectId, String id) {
        projects.project(projectId);
        var rows = jdbc.query("SELECT * FROM ai_conversation WHERE id=? AND project_id=?", (rs, n) -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id); map.put("scope", rs.getString("scope")); map.put("targetId", rs.getString("target_id")); map.put("targetType", rs.getString("target_type"));
            return map;
        }, id, projectId);
        if (rows.isEmpty()) throw Problem.missing(); return rows.getFirst();
    }
    public List<Map<String, Object>> messages(String projectId, String id) {
        get(projectId, id);
        return jdbc.query("SELECT * FROM ai_message WHERE conversation_id=? ORDER BY created_at,id", (rs, n) -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", rs.getString("id")); result.put("role", rs.getString("role")); result.put("content", rs.getString("content")); result.put("status", rs.getString("status"));
            result.put("createdAt", rs.getTimestamp("created_at").toInstant()); result.put("baseVersion", rs.getString("base_version")); result.put("appliedVersion", rs.getString("applied_version"));
            result.put("candidate", rs.getString("candidate") == null ? null : json.tree(rs.getString("candidate")));
            result.put("validation", rs.getString("validation") == null ? null : json.tree(rs.getString("validation")));
            return result;
        }, id);
    }
    public String recordUser(String conversationId, String jobId, String feedback, String baseVersion, Object context) {
        String id = Ids.newId();
        jdbc.update("INSERT INTO ai_message(id,conversation_id,job_id,role,content,status,base_version,context_snapshot,created_at) VALUES(?,?,?,'user',?,'SUBMITTED',?,?,?)", id, conversationId, jobId, feedback, baseVersion == null ? null : Long.parseLong(baseVersion), json.write(context), Timestamp.from(Instant.now()));
        return id;
    }
    public void recordAssistant(String conversationId, String jobId, String content, String status, String baseVersion, String appliedVersion, Object candidate, Object validation, String modelVersion, String templateVersion) {
        jdbc.update("INSERT INTO ai_message(id,conversation_id,job_id,role,content,status,base_version,applied_version,candidate,validation,model_version,template_version,created_at) VALUES(?,?,?,'assistant',?,?,?,?,?,?,?,?,?)", Ids.newId(), conversationId, jobId, content, status,
                baseVersion == null ? null : Long.parseLong(baseVersion), appliedVersion == null ? null : Long.parseLong(appliedVersion), json.write(candidate), json.write(validation), modelVersion, templateVersion, Timestamp.from(Instant.now()));
    }
    public static String stableId(String prefix, String content) { return prefix.toLowerCase() + "_" + UUID.nameUUIDFromBytes(content.getBytes(StandardCharsets.UTF_8)).toString().replace("-", ""); }
}
