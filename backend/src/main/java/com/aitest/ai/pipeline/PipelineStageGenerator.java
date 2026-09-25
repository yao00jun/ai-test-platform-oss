package com.aitest.ai.pipeline;

import com.aitest.ai.*;
import com.aitest.asset.*;
import com.aitest.analysis.GenerationEvidenceService;
import com.aitest.analysis.UiSourceValidator;
import com.aitest.common.*;
import com.aitest.engine.sql.DatabaseSchemaService;
import com.aitest.engine.web.PageEvidenceService;
import com.aitest.execution.*;
import com.aitest.job.JobContext;
import com.aitest.requirement.DocumentParser;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Each completed batch is a durable checkpoint; retries never recreate that batch's assets. */
@Service
public final class PipelineStageGenerator {
    private static final String SOURCES = hash("stage-sources");
    private static final String SUMMARY = hash("stage-summary");
    private final AssetService assets;
    private final PipelineRepository pipelines;
    private final AiDraftEngine drafts;
    private final AiChangeSetService changes;
    private final AiConversationService conversations;
    private final ModelSettingsService settings;
    private final PromptCatalog prompts;
    private final JsonCodec json;
    private final DocumentParser documents;
    private final DatabaseSchemaService schemas;
    private final PageEvidenceService pages;
    private final ScenarioDependencyValidator dependencies;
    private final GenerationEvidenceService evidence;
    /** Definitions per model call: every one needs at least one case in the reply, so the batch also bounds the output size. */
    private final int apiBatchSize;
    /** Generated API cases judged per S4 model call; one call per case exhausts low requests-per-minute quotas. */
    private final int sqlBatchSize;
    private static final int API_COVERAGE_ROUNDS = 3;
    public PipelineStageGenerator(AssetService assets, PipelineRepository pipelines, AiDraftEngine drafts, AiChangeSetService changes, AiConversationService conversations, ModelSettingsService settings, PromptCatalog prompts, JsonCodec json, DocumentParser documents, DatabaseSchemaService schemas, PageEvidenceService pages, ScenarioDependencyValidator dependencies, GenerationEvidenceService evidence,
                                  @org.springframework.beans.factory.annotation.Value("${aitest.pipeline.api-batch-size:8}") int apiBatchSize,
                                  @org.springframework.beans.factory.annotation.Value("${aitest.pipeline.sql-batch-size:8}") int sqlBatchSize) {
        this.apiBatchSize = Math.max(1, Math.min(50, apiBatchSize));
        this.sqlBatchSize = Math.max(1, Math.min(50, sqlBatchSize));
        this.assets = assets; this.pipelines = pipelines; this.drafts = drafts; this.changes = changes; this.conversations = conversations; this.settings = settings; this.prompts = prompts; this.json = json; this.documents = documents; this.schemas = schemas; this.pages = pages; this.dependencies = dependencies; this.evidence = evidence;
    }
    public Map<String, Object> run(String stage, JobContext job, Map<String, Object> pipeline) {
        Map<String, Object> completed = pipelines.batch(id(pipeline), stage, SUMMARY);
        if (completed != null) return completed;
        if (stage.equals("S1")) {
            completed = pipelines.batch(id(pipeline), stage, hash("combined"));
            if (completed != null) return completed;
        }
        bindSources(stage, job, pipeline);
        Map<String, Object> result = switch (stage) {
            case "S1" -> analyze(job, pipeline);
            case "S2" -> functional(job, pipeline);
            case "S3" -> api(job, pipeline);
            case "S4" -> sql(job, pipeline);
            case "S5" -> web(job, pipeline);
            default -> throw Problem.invalid("未知生成阶段");
        };
        return job.atomic(() -> {
            pipelines.requireActive(job.projectId(), id(pipeline), job.id());
            pipelines.saveBatch(id(pipeline), stage, SUMMARY, Map.of(), result, List.of());
            return result;
        });
    }
    private Map<String, Object> analyze(JobContext job, Map<String, Object> pipeline) {
        var completed = pipelines.batch(id(pipeline), "S1", hash("combined"));
        if (completed != null) return completed;
        List<Asset> requirements = selected(job, pipeline, "requirementIds"); List<AiChangeSetService.Proposal> merged = new ArrayList<>(); int total = 0;
        for (Asset requirement : requirements) {
            if (requirement.confirmed()) throw new Problem(409, "PROTECTED_ASSET", "需求已人工保护，请保留其分析并通过全局反馈选择需要更新的内容");
            List<Map<String, Object>> analyzed = new ArrayList<>();
            for (var chunk : chunks(requirement)) {
                job.checkpoint(); String key = hash(requirement.id() + ":" + requirement.version() + ":" + chunk.index());
                var cached = pipelines.batch(id(pipeline), "S1", key);
                if (cached != null) { analyzed.add(cached); continue; }
                var context = context("S1", Set.of(AssetType.REQUIREMENT), List.of(requirement), Map.of("chunk", chunk, "instruction", "只分析 chunk 这一个需求片段，按系统提示的 analysis 结构返回一项 MODIFY，targetId 与 baseVersion 取自 sources[0]。"));
                var draft = generate(job, pipeline, "ba_requirement_parser", context, null, null, false, changes -> {
                    if (changes.size() != 1) throw Problem.invalid("需求片段分析只能返回一项 MODIFY（本次返回了 " + changes.size() + " 项）");
                    var only = changes.getFirst();
                    if (!"MODIFY".equals(only.operation()) || only.targetType() != AssetType.REQUIREMENT || only.data() == null || !only.data().containsKey("analysis"))
                        throw Problem.invalid("需求分析必须是一项 operation 为 MODIFY、targetType 为 REQUIREMENT 的变更，data 只包含 analysis");
                    if (analysisObject(only.data().get("analysis")).isEmpty()) throw Problem.invalid("analysis 不能为空，请按系统提示的 7 个键给出分析");
                    // Only the analysis is used; the requirement and its version are known here, so they are filled in rather than checked.
                    return List.of(new AiChangeSetService.Proposal("MODIFY", AssetType.REQUIREMENT, requirement.id(), null, null, requirement.version(), only.name(), Map.of("analysis", only.data().get("analysis"))));
                });
                var proposal = draft.changes().getFirst();
                Map<String, Object> analysis = analysisObject(proposal.data().get("analysis"));
                Map<String, Object> output = Map.of("index", chunk.index(), "offset", chunk.offset(), "title", chunk.title(), "analysis", analysis);
                job.atomic(() -> { pipelines.requireActive(job.projectId(), id(pipeline), job.id()); requireBoundSources(job.projectId(), id(pipeline), "S1"); requireCurrent(job.projectId(), List.of(requirement)); pipelines.saveBatch(id(pipeline), "S1", key, context, output, List.of()); return output; });
                analyzed.add(output);
            }
            total += analyzed.size();
            merged.add(new AiChangeSetService.Proposal("MODIFY", AssetType.REQUIREMENT, requirement.id(), null, null, requirement.version(), null, Map.of("analysis", Map.of("chunks", analyzed, "sourceHash", hash(Values.text(requirement.data(), "content", "")), "coveredChunks", analyzed.size()))));
        }
        return apply(job, pipeline, "S1", hash("combined"), merged, requirements, Map.of("requirements", requirements, "coveredChunks", total, "requirementsCount", requirements.size()), "", true);
    }
    /** Models return the analysis as an object, a list of sections or plain text; only the object form is stored, so the others are wrapped. */
    static Map<String, Object> analysisObject(Object value) {
        if (value == null) return Map.of();
        if (value instanceof Map<?, ?>) return Values.map(value);
        if (value instanceof List<?> list) {
            if (list.isEmpty()) return Map.of();
            return Map.of("sections", list.stream().map(item -> item instanceof Map<?, ?> ? Values.map(item) : Map.of("content", Objects.toString(item, ""))).toList());
        }
        if (value instanceof String text) return text.isBlank() ? Map.of() : Map.of("summary", text);
        throw Problem.invalid("需求分析 analysis 需要 JSON 对象，模型返回了 " + value.getClass().getSimpleName());
    }
    private Map<String, Object> functional(JobContext job, Map<String, Object> pipeline) {
        List<String> ids = new ArrayList<>(); int covered = 0;
        for (Asset requirement : selected(job, pipeline, "requirementIds")) for (var chunk : chunks(requirement)) {
            String key = hash(requirement.id() + ":" + requirement.version() + ":" + chunk.index());
            var cached = pipelines.batch(id(pipeline), "S2", key);
            if (cached != null) { ids.addAll(strings(cached.get("assetIds"))); covered++; continue; }
            Map<String, Object> context = context("S2", Set.of(AssetType.FUNCTIONAL_CASE, AssetType.FUNCTIONAL_STEP), List.of(requirement), Map.of("chunk", chunk, "instruction", "只为当前需求片段生成功能、边界、异常用例；使用 featureCaseStart/featureCaseEnd 成对 Markdown 和合法步骤表格。"));
            context.remove("schemas"); // The reply is Markdown; the JSON field schemas would only cost tokens.
            var draft = generate(job, pipeline, "functional_case_generation", context, AssetType.FUNCTIONAL_CASE, null, false);
            List<AiChangeSetService.Proposal> proposals = new ArrayList<>();
            for (var proposal : draft.changes()) {
                Map<String, Object> data = new LinkedHashMap<>(proposal.data());
                if (proposal.targetType() == AssetType.FUNCTIONAL_CASE) { data.put("requirementId", requirement.id()); data.put("tags", List.of("pipeline:" + id(pipeline), "chunk:" + chunk.index())); }
                proposals.add(new AiChangeSetService.Proposal(proposal.operation(), proposal.targetType(), proposal.targetId(), proposal.parentId(), proposal.localKey(), proposal.baseVersion(), proposal.name(), data));
            }
            var output = apply(job, pipeline, "S2", key, proposals, List.of(requirement), context, draft.raw(), true); ids.addAll(strings(output.get("assetIds"))); covered++;
            job.progress(Math.min(95, covered), "已生成 " + covered + " 个需求片段的功能用例");
        }
        return Map.of("assetIds", ids, "coveredChunks", covered);
    }
    private Map<String, Object> api(JobContext job, Map<String, Object> pipeline) {
        List<Asset> definitions = selected(job, pipeline, "apiDefinitionIds");
        if (definitions.isEmpty()) throw blocked("未导入开发接口文档，接口和场景生成需要真实 API 定义");
        List<String> ids = new ArrayList<>();
        // Orientation only: cases may target sources alone, so ids here would just invite out-of-batch references.
        List<Map<String, Object>> apiIndex = definitions.stream().map(api -> Map.of("name", api.name(), "method", api.data().get("method"), "path", api.data().get("path"))).toList();
        for (int offset = 0; offset < definitions.size(); offset += apiBatchSize) {
            List<Asset> batch = definitions.subList(offset, Math.min(offset + apiBatchSize, definitions.size()));
            // Coverage is completed over several rounds: each round asks only for the definitions still lacking a case,
            // and every round's cases are saved as soon as they pass, so a model that trails off does not lose the batch.
            List<Asset> pending = new ArrayList<>(batch);
            for (int round = 1; !pending.isEmpty(); round++) {
                String key = sourceKey(pending);
                var cached = pipelines.batch(id(pipeline), "S3", key);
                if (cached != null) {
                    ids.addAll(strings(cached.get("assetIds")));
                    Set<String> done = new HashSet<>(strings(cached.get("coveredDefinitionIds")));
                    if (done.isEmpty()) break; // Batches saved before per-round coverage tracking always covered everything.
                    pending.removeIf(api -> done.contains(api.id())); continue;
                }
                if (round > API_COVERAGE_ROUNDS) throw new Problem(422, AiDraftEngine.OUT_OF_SCOPE, "当前 API 批次经 " + API_COVERAGE_ROUNDS + " 轮生成仍未覆盖：" + describe(pending) + "，已生成的接口用例已保存，请恢复流水线重试或先手工补充这些接口的用例");
                List<Map<String, Object>> prior = priorAssetsForApiStage(tracked(job, pipeline));
                String instruction = (round == 1 ? "为 sources 中的每个接口（共 " + pending.size() + " 个）生成 1–3 条 API_CASE（必含 1 条正向用例，边界/异常用例只在接口有明确校验规则时补充），逐个覆盖，不得遗漏；" : "上一轮遗漏了 sources 中的这 " + pending.size() + " 个接口，本轮只为它们各生成 1–3 条 API_CASE，不要重复旧批次已有的接口用例；")
                        + "apiDefinitionId 与 method 取自对应接口，path 沿用文档路径并把路径参数替换为取值（规则见系统提示）。有业务先后关系的用例串成本批新增 SCENARIO 与 SCENARIO_STEP，步骤 parentId 写 \"@<本批 SCENARIO 的 localKey>\"，不得向已有场景追加步骤。先提取变量再使用。";
                Map<String, Object> context = context("S3", Set.of(AssetType.API_CASE, AssetType.SCENARIO, AssetType.SCENARIO_STEP), pending, Map.of("existingAssets", prior, "apiIndex", apiIndex, "instruction", instruction));
                Set<String> covered = new LinkedHashSet<>();
                List<Asset> target = pending;
                var draft = generate(job, pipeline, "api_case_generation", context, null, null, false, changes -> {
                    // Everything below can be put right by the model, so it is reported inside the repair loop.
                    List<AiChangeSetService.Proposal> owned = new ArrayList<>();
                    covered.clear();
                    for (var proposal : ownScenarioSteps(changes)) {
                        allowAdd(proposal, Set.of(AssetType.API_CASE, AssetType.SCENARIO, AssetType.SCENARIO_STEP));
                        if (proposal.targetType() != AssetType.API_CASE) { owned.add(proposal); continue; }
                        Asset definition = batch.stream().filter(api -> api.id().equals(proposal.data().get("apiDefinitionId"))).findFirst()
                                .orElseThrow(() -> Problem.invalid("接口用例「" + proposal.name() + "」的 apiDefinitionId 不是本轮 sources 中的接口；只能为 sources 列出的接口生成用例"));
                        owned.add(documentedRequest(proposal, definition));
                        covered.add(definition.id());
                    }
                    if (target.stream().noneMatch(api -> covered.contains(api.id()))) throw Problem.invalid("本轮没有为任何待覆盖接口生成用例，请为这些接口各生成至少 1 条 API_CASE：" + describe(target));
                    return preflight(job.projectId(), owned);
                });
                List<String> coveredNow = target.stream().map(Asset::id).filter(covered::contains).toList();
                context.put("coveredDefinitionIds", coveredNow);
                var output = apply(job, pipeline, "S3", key, draft.changes(), pending, context, draft.raw(), true); ids.addAll(strings(output.get("assetIds")));
                pending.removeIf(api -> coveredNow.contains(api.id()));
                job.progress(Math.min(95, (offset + batch.size() - pending.size()) * 100 / definitions.size()), "已覆盖 " + (offset + batch.size() - pending.size()) + "/" + definitions.size() + " 个接口");
            }
        }
        return Map.of("assetIds", ids, "coveredDefinitions", definitions.size());
    }
    private static String describe(List<Asset> definitions) { return definitions.stream().map(api -> api.data().get("method") + " " + api.data().get("path")).toList().toString(); }
    /**
     * Each stage only gets the evidence domains it can act on: backend facts for analysis, cases and API work, DDL for
     * SQL, page selectors and analysis diagnostics only for UI. The rest of the snapshot is noise that costs tokens.
     */
    /** One line per column ("name TYPE NOT NULL PK -- remark"): the facts of the metadata maps in about half the characters. */
    static Map<String, Object> compactSchema(Map<String, Object> schema) {
        List<Map<String, Object>> tables = new ArrayList<>();
        for (var table : Values.objects(schema.get("tables"))) {
            Set<String> keys = new HashSet<>(strings(table.get("primaryKeys")));
            List<String> columns = Values.objects(table.get("columns")).stream().map(column -> {
                String name = Values.text(column, "name", ""), remark = Values.text(column, "remarks", "");
                return name + " " + Values.text(column, "type", "") + (Values.bool(column, "nullable", true) ? "" : " NOT NULL") + (keys.contains(name) ? " PK" : "") + (remark.isBlank() ? "" : " -- " + remark);
            }).toList();
            Map<String, Object> compact = new LinkedHashMap<>(); compact.put("name", table.get("name")); compact.put("columns", columns);
            List<String> references = Values.objects(table.get("references")).stream().map(reference -> reference.get("column") + " -> " + reference.get("targetTable") + "." + reference.get("targetColumn")).toList();
            if (!references.isEmpty()) compact.put("references", references);
            tables.add(compact);
        }
        Map<String, Object> result = new LinkedHashMap<>(); result.put("databaseSourceId", schema.get("databaseSourceId")); result.put("tables", tables);
        return result;
    }
    /** The domains a stage acts on, most important first: the evidence budget is spent in this order. */
    static List<String> evidenceDomains(String stage) {
        return switch (stage) { case "S4" -> List.of("database", "backend"); case "S5" -> List.of("frontend", "backend"); default -> List.of("backend"); };
    }
    /** Characters of static evidence per call; requirement chunks only need a sample of the implemented rules. */
    static int evidenceBudget(String stage) {
        return switch (stage) { case "S1" -> 30_000; case "S2" -> 40_000; default -> 80_000; };
    }
    static Map<String, Object> scopedEvidence(String stage, Map<String, Object> full) {
        if (full.isEmpty()) return full;
        Set<String> domains = switch (stage) {
            case "S4" -> Set.of("backend", "database");
            case "S5" -> Set.of("frontend", "backend");
            default -> Set.of("backend");
        };
        Map<String, Object> scoped = new LinkedHashMap<>();
        for (var entry : full.entrySet()) {
            String key = entry.getKey();
            if (Set.of("frontend", "backend", "database").contains(key)) { if (domains.contains(key)) scoped.put(key, entry.getValue()); continue; }
            if (key.equals("diagnostics")) {
                if (stage.equals("S5")) scoped.put(key, Values.objects(entry.getValue()).stream().filter(item -> "FRONTEND".equals(item.get("kind"))).toList());
                else if (entry.getValue() instanceof List<?> list && !list.isEmpty()) scoped.put(key, Map.of("count", list.size(), "note", "静态分析告警仅在 UI 阶段提供"));
                continue;
            }
            scoped.put(key, entry.getValue());
        }
        scoped.put("scope", domains);
        return scoped;
    }
    /**
     * S3 only needs to know which interface cases and scenarios already exist (to reference, not to recreate) and what the
     * functional cases are called. Steps, requirement text and case bodies stay out; the list is bounded so late batches
     * do not pay for early ones.
     */
    static List<Map<String, Object>> priorAssetsForApiStage(List<Asset> tracked) {
        List<Map<String, Object>> result = new ArrayList<>(); int functionalCases = 0, apiCases = 0, scenarios = 0;
        for (Asset asset : tracked) {
            switch (asset.type()) {
                case API_CASE -> { if (apiCases++ < 200) result.add(Map.of("id", asset.id(), "type", asset.type(), "name", asset.name(), "apiDefinitionId", Objects.toString(asset.data().get("apiDefinitionId"), ""))); }
                case SCENARIO -> { if (scenarios++ < 60) result.add(Map.of("id", asset.id(), "type", asset.type(), "name", asset.name())); }
                case FUNCTIONAL_CASE -> { if (functionalCases++ < 60) result.add(Map.of("id", asset.id(), "type", asset.type(), "name", asset.name())); }
                default -> { }
            }
        }
        int omitted = Math.max(0, functionalCases - 60) + Math.max(0, apiCases - 200) + Math.max(0, scenarios - 60);
        if (omitted > 0) result.add(Map.of("type", "SUMMARY", "name", "另有 " + omitted + " 个既有资产未列出（功能用例保留 60 条名称，接口用例 200 条，场景 60 条）"));
        return result;
    }
    /**
     * An imported definition carries the whole OpenAPI document; the model only needs this operation, its path item and
     * the component schemas they reference (transitively), which turns ~37k characters per definition into a few thousand.
     */
    public static Map<String, Object> slimApiSchema(Object value) {
        if (!(value instanceof Map<?, ?>)) return Map.of();
        Map<String, Object> schema = Values.map(value); Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of("sourceFormat", "sourceVersion", "serverUrl", "operation", "pathItem")) if (schema.get(key) != null) result.put(key, schema.get(key));
        Map<String, Object> components = Values.map(Values.map(schema.get("document")).get("components"));
        Map<String, Object> all = Values.map(components.get("schemas"));
        if (all.isEmpty()) return result;
        Set<String> wanted = new LinkedHashSet<>(); collectRefs(result, wanted);
        Map<String, Object> kept = new LinkedHashMap<>(); Deque<String> queue = new ArrayDeque<>(wanted); int budget = 24_000; int omitted = 0;
        while (!queue.isEmpty()) {
            String name = queue.poll(); if (kept.containsKey(name) || !all.containsKey(name)) continue;
            Object component = all.get(name); int length = new JsonCodec().write(component).length();
            if (length > budget) { omitted++; continue; }
            kept.put(name, component); budget -= length;
            Set<String> nested = new LinkedHashSet<>(); collectRefs(component, nested); nested.removeAll(kept.keySet()); queue.addAll(nested);
        }
        if (!kept.isEmpty()) result.put("components", Map.of("schemas", kept));
        if (omitted > 0) result.put("omittedComponents", omitted);
        return result;
    }
    private static void collectRefs(Object node, Set<String> into) {
        if (node instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                if ("$ref".equals(entry.getKey()) && entry.getValue() instanceof String ref && ref.startsWith("#/components/schemas/")) into.add(ref.substring("#/components/schemas/".length()));
                else collectRefs(entry.getValue(), into);
            }
        } else if (node instanceof List<?> list) list.forEach(item -> collectRefs(item, into));
    }
    private Map<String, Object> sql(JobContext job, Map<String, Object> pipeline) {
        List<Asset> sources = selected(job, pipeline, "databaseSourceIds");
        if (sources.isEmpty() && !evidence.hasDdl(job.projectId(), source(pipeline))) throw blocked("未提供 DDL 或真实业务数据库连接，SQL 阶段保留为待补充");
        List<Asset> cases = tracked(job, pipeline).stream().filter(asset -> asset.type() == AssetType.API_CASE).toList();
        if (cases.isEmpty()) throw blocked("没有已生成的 HTTP 用例可绑定后置 SQL");
        List<Map<String, Object>> structures = sources.stream().map(source -> schemas.schema(job.projectId(), source.id())).toList();
        List<String> ids = new ArrayList<>(); int checked = 0;
        List<Asset> pending = new ArrayList<>();
        for (Asset api : cases) {
            // Pipelines started before batching saved one checkpoint per case; those cases are done.
            var cached = pipelines.batch(id(pipeline), "S4", hash(api.id() + ":" + api.version()));
            if (cached != null) { ids.addAll(strings(cached.get("assetIds"))); checked++; } else pending.add(api);
        }
        for (int offset = 0; offset < pending.size(); offset += sqlBatchSize) {
            List<Asset> batch = pending.subList(offset, Math.min(offset + sqlBatchSize, pending.size()));
            String key = hash("sql-batch:" + sourceKey(batch)); var cached = pipelines.batch(id(pipeline), "S4", key);
            if (cached != null) { ids.addAll(strings(cached.get("assetIds"))); checked += batch.size(); continue; }
            List<Asset> basis = new ArrayList<>(sources); basis.addAll(batch);
            Set<String> parents = new HashSet<>(batch.stream().map(Asset::id).toList());
            List<Map<String, Object>> targets = batch.stream().map(api -> Map.<String, Object>of("id", api.id(), "name", api.name(), "method", Values.text(api.data(), "method", ""), "path", Values.text(api.data(), "path", ""))).toList();
            Map<String, Object> context = context("S4", Set.of(AssetType.SQL_VALIDATION), basis, Map.of("databaseSchemas", structures.stream().map(PipelineStageGenerator::compactSchema).toList(), "targetCases", targets, "instruction",
                    "逐个判断 targetCases 中的用例执行后是否需要后置 SQL 校验（完整请求内容见 sources 中同 id 的记录），需要的生成 SQL_VALIDATION 并以该用例 id 作为 parentId。"
                            + (sources.isEmpty() ? "没有可连接的数据源：databaseSourceId 写空字符串，依据 sourceEvidence.database 的 DDL 生成待绑定草稿。" : "databaseSourceId 只能取 databaseSchemas 中的数据源 id。")
                            + "整批都不需要时返回空 changes 和具体 reason。"));
            var draft = generate(job, pipeline, "sql_validation_generation", context, null, null, true, changes -> {
                for (var proposal : changes) {
                    allowAdd(proposal, Set.of(AssetType.SQL_VALIDATION));
                    String database = Values.text(proposal.data(), "databaseSourceId", "");
                    if (!parents.contains(proposal.parentId())) throw Problem.invalid(label(proposal) + "的 parentId 必须是 targetCases 中某个用例的 id");
                    if (sources.isEmpty() && !database.isBlank()) throw Problem.invalid(label(proposal) + "：本次没有可连接的数据源，databaseSourceId 必须写空字符串");
                    if (!sources.isEmpty() && sources.stream().noneMatch(source -> source.id().equals(database))) throw Problem.invalid(label(proposal) + "的 databaseSourceId 必须是 databaseSchemas 中的数据源 id");
                    if (Values.objects(proposal.data().get("assertions")).isEmpty()) throw Problem.invalid(label(proposal) + "必须包含依据需求的断言");
                    if (!database.isBlank() && source(pipeline).isBlank()) {
                        try { schemas.validateReadQuery(job.projectId(), database, Values.text(proposal.data(), "sql", "")); }
                        catch (Problem problem) { if (problem.status() != 422) throw problem; throw Problem.invalid(label(proposal) + "：" + problem.getMessage()); }
                    }
                }
                return preflight(job.projectId(), changes);
            });
            var output = apply(job, pipeline, "S4", key, draft.changes(), basis, context, draft.raw(), true); ids.addAll(strings(output.get("assetIds"))); checked += batch.size();
            job.progress(Math.min(95, checked * 100 / cases.size()), "已判断 " + checked + "/" + cases.size() + " 个接口用例的后置 SQL");
        }
        return Map.of("assetIds", ids, "checkedCases", checked, "databaseSchemas", structures);
    }
    private Map<String, Object> web(JobContext job, Map<String, Object> pipeline) {
        List<Asset> requirements = selected(job, pipeline, "requirementIds");
        String key = hash("web:" + sourceKey(requirements)); var cached = pipelines.batch(id(pipeline), "S5", key); if (cached != null) return cached;
        Map<String, Object> config = Values.map(pipeline.get("config")); String environment = Values.text(config, "environmentId", "");
        List<Asset> recordings = recordingAssets(job, pipeline);
        Asset environmentBasis = environment.isBlank() ? null : assets.get(job.projectId(), environment);
        String configuredBase = environmentBasis == null ? "" : Values.text(environmentBasis.data(), "webUrl", "");
        Map<String, Object> observation = Map.of();
        if (!configuredBase.isBlank()) {
            try {
                observation = pages.capture(job, environment);
                if (!Objects.equals(observation.get("environmentVersion"), environmentBasis.version())) throw sourceChanged();
            } catch (Problem unavailable) {
                if (!"PAGE_EVIDENCE_UNAVAILABLE".equals(unavailable.code())) throw unavailable;
                job.status("页面证据采集失败，正在检查人工录制和静态组件证据");
                observation = Map.of();
            }
        }
        Map<String, Object> staticUi = evidence.frontend(job.projectId(), source(pipeline));
        if (observation.isEmpty() && recordings.isEmpty() && Values.objects(staticUi.get("selectors")).isEmpty()) throw blocked("缺少页面、人工录制或静态组件证据，UI 定位器不能凭需求猜测");
        Set<String> locators = new HashSet<>(); collectLocators(observation, "", locators);
        for (Asset step : recordings) if (step.type() == AssetType.UI_STEP) {
            String frame = Values.text(step.data(), "frame", "");
            for (String field : List.of("selector", "targetSelector")) if (!Values.text(step.data(), field, "").isBlank()) locators.add(frame + "\n" + step.data().get(field));
        }
        var context = context("S5", Set.of(AssetType.UI_SCENARIO, AssetType.UI_STEP), requirements, Map.of("evidence", observation, "recordings", recordings, "configuredBaseUrl", configuredBase, "instruction", "依据真实页面/录制和 sourceEvidence 的静态组件生成 UI 场景及独立步骤。selector、targetSelector、frame、baseUrl、导航 url 只能来自证据。缺少真实地址时 baseUrl 留空，只生成待配置步骤，不虚构 navigate。静态定位需运行验证。步骤归属本批新增场景，不更改原录制资产；不提供虚构凭证。无法定位需求目标则返回 blocked。"));
        context.put("runtimeEvidence", UiSourceValidator.capture(observation, recordings, configuredBase));
        Map<String, Object> observed = observation;
        var draft = generate(job, pipeline, "ui_case_generation", context, null, null, true, changes -> {
            if (source(pipeline).isBlank()) UiGenerationEvidence.validate(changes, observed, recordings, configuredBase);
            Set<String> newScenarios = new HashSet<>();
            for (var proposal : changes) if (proposal.targetType() == AssetType.UI_SCENARIO && proposal.localKey() != null) newScenarios.add("@" + proposal.localKey());
            for (var proposal : changes) {
                allowAdd(proposal, Set.of(AssetType.UI_SCENARIO, AssetType.UI_STEP));
                if (proposal.targetType() != AssetType.UI_STEP) continue;
                if (!newScenarios.contains(proposal.parentId())) throw Problem.invalid(label(proposal) + "的 parentId 必须是本批新增 UI_SCENARIO 的 @localKey");
                String frame = Values.text(proposal.data(), "frame", "");
                for (String field : List.of("selector", "targetSelector")) {
                    String selector = Values.text(proposal.data(), field, "");
                    if (source(pipeline).isBlank() && !selector.isBlank() && !locators.contains(frame + "\n" + selector)) throw Problem.invalid(label(proposal) + "的定位器 " + selector + " 没有页面或录制证据，只能使用 evidence 或 recordings 中出现过的定位器");
                }
            }
            return preflight(job.projectId(), changes);
        });
        List<Asset> basis = new ArrayList<>(requirements); basis.addAll(recordings); if (environmentBasis != null) basis.add(environmentBasis);
        return apply(job, pipeline, "S5", key, draft.changes(), basis, context, draft.raw(), true);
    }
    /**
     * Steps must hang off a scenario created in the same batch. Models regularly omit parentId when the batch has a
     * single scenario; that case is filled in, everything else is reported with the step name and the keys it may use.
     */
    static List<AiChangeSetService.Proposal> ownScenarioSteps(List<AiChangeSetService.Proposal> changes) {
        List<String> scenarios = changes.stream().filter(p -> "ADD".equals(p.operation()) && p.targetType() == AssetType.SCENARIO && p.localKey() != null && !p.localKey().isBlank()).map(p -> "@" + p.localKey()).toList();
        List<AiChangeSetService.Proposal> result = new ArrayList<>(changes.size());
        for (var proposal : changes) {
            if (proposal.targetType() != AssetType.SCENARIO_STEP) { result.add(proposal); continue; }
            String parent = proposal.parentId() == null ? "" : proposal.parentId().strip();
            if (!parent.isEmpty() && !parent.startsWith("@") && scenarios.contains("@" + parent)) parent = "@" + parent;
            if (parent.isEmpty() && scenarios.size() == 1) parent = scenarios.getFirst();
            if (parent.isEmpty()) throw Problem.invalid("场景步骤「" + proposal.name() + "」缺少 parentId，必须填写本批新增场景的 @localKey" + (scenarios.isEmpty() ? "；本批还没有新增任何 SCENARIO" : "，可选：" + scenarios));
            if (!scenarios.contains(parent)) throw new Problem(422, AiDraftEngine.OUT_OF_SCOPE, "场景步骤「" + proposal.name() + "」的 parentId " + parent + " 不是本批新增场景，不能追加到已有场景" + (scenarios.isEmpty() ? "" : "，可选：" + scenarios));
            result.add(new AiChangeSetService.Proposal(proposal.operation(), proposal.targetType(), proposal.targetId(), parent, proposal.localKey(), proposal.baseVersion(), proposal.name(), proposal.data()));
        }
        return result;
    }
    private AiDraftEngine.Draft generate(JobContext job, Map<String, Object> pipeline, String template, Map<String, Object> context, AssetType type, String parent, boolean allowEmpty) {
        return generate(job, pipeline, template, context, type, parent, allowEmpty, java.util.function.UnaryOperator.identity());
    }
    private AiDraftEngine.Draft generate(JobContext job, Map<String, Object> pipeline, String template, Map<String, Object> context, AssetType type, String parent, boolean allowEmpty, java.util.function.UnaryOperator<List<AiChangeSetService.Proposal>> normalize) {
        String stage = String.valueOf(context.get("stage"));
        context.put("sourceEvidence", scopedEvidence(stage, evidence.context(job.projectId(), source(pipeline), evidenceDomains(stage), evidenceBudget(stage))));
        String conversation = pipeline.get("conversationId").toString(); conversations.recordUser(conversation, job.id(), context.get("stage") + " 自动生成", null, context);
        String stamp = "unconfigured";
        try {
            ModelSettings model = settings.current(); stamp = model.modelName() + ":" + model.version();
            // runtimeEvidence is the server's own copy of the UI locators, checked after generation; the model already sees them.
            Map<String, Object> prompt = new LinkedHashMap<>(context); prompt.remove("runtimeEvidence");
            var draft = drafts.generate(model, job, template, prompt, type, parent, type == AssetType.FUNCTIONAL_CASE ? "仅使用原模板的 featureCaseStart/featureCaseEnd Markdown 契约。当前用户消息含原始需求 chunk，请覆盖这个完整片段。" : prompts.load("pipeline_stage_contract"), allowEmpty, normalize);
            conversations.recordAssistant(conversation, job.id(), draft.raw(), "PREVIEW", null, null, draft.changes(), Map.of("parsed", true), stamp, prompts.version(template));
            return draft;
        } catch (RuntimeException error) {
            String raw = error instanceof Problem problem && problem.details() instanceof Map<?, ?> details ? Objects.toString(details.get("rawOutput"), "") : "";
            boolean interrupted = Thread.interrupted();
            try { conversations.recordAssistant(conversation, job.id(), raw, "FAILED", null, null, Map.of("raw", raw), Map.of("message", error instanceof Problem ? error.getMessage() : "阶段生成失败"), stamp, prompts.version(template)); }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
            throw error;
        }
    }
    private Map<String, Object> apply(JobContext job, Map<String, Object> pipeline, String stage, String key, List<AiChangeSetService.Proposal> proposals, List<Asset> basis, Object context, String raw, boolean saveBatch) {
        List<AiChangeSetService.Proposal> grounded = evidence.ground(job.projectId(), source(pipeline), proposals, Values.map(Values.map(context).get("runtimeEvidence")));
        return job.atomic(() -> {
            pipelines.requireActive(job.projectId(), id(pipeline), job.id());
            requireBoundSources(job.projectId(), id(pipeline), stage); requireCurrent(job.projectId(), basis);
            List<String> ids = new ArrayList<>(); String changeId = "";
            if (!proposals.isEmpty()) {
                changeId = changes.create(job.projectId(), pipeline.get("conversationId").toString(), job.id(), grounded);
                var result = changes.apply(job.projectId(), changeId, AiGenerationService.changeIds(changes.get(job.projectId(), changeId)));
                for (Object value : (List<?>) result.get("assets")) { Asset asset = json.convert(value, Asset.class); ids.add(asset.id()); }
                String environment = Values.text(Values.map(pipeline.get("config")), "environmentId", "");
                for (String assetId : ids) if (Set.of(AssetType.SCENARIO, AssetType.UI_SCENARIO).contains(assets.get(job.projectId(), assetId).type())) {
                    var validation = dependencies.validate(job.projectId(), assetId, environment, null);
                    if (!Boolean.TRUE.equals(validation.get("valid")) && !ExecutionConfiguration.onlyConfigurationGaps(validation)) throw new Problem(422, "GENERATED_DEPENDENCY_INVALID", "生成的步骤变量或顺序不完整，未保存此批次", validation.get("errors"));
                }
            }
            Map<String, Object> output = new LinkedHashMap<>(Map.of("assetIds", ids, "changeSetId", changeId, "raw", raw, "status", proposals.isEmpty() ? "NOT_APPLICABLE" : "APPLIED"));
            if (stage.equals("S1")) { output.put("coveredChunks", Values.map(context).get("coveredChunks")); output.put("requirements", Values.map(context).get("requirementsCount")); }
            if (stage.equals("S3") && Values.map(context).get("coveredDefinitionIds") != null) output.put("coveredDefinitionIds", Values.map(context).get("coveredDefinitionIds"));
            if (saveBatch) pipelines.saveBatch(id(pipeline), stage, key, context, output, ids);
            pipelines.jdbc().update("UPDATE ai_message SET status='APPLIED' WHERE job_id=? AND role='assistant' AND status='PREVIEW'", job.id());
            return output;
        });
    }
    private void requireCurrent(String project, List<Asset> basis) { for (Asset asset : basis) AssetService.requireVersion(assets.get(project, asset.id()), asset.version()); }
    private void bindSources(String stage, JobContext job, Map<String, Object> pipeline) {
        job.atomic(() -> {
            pipelines.requireActive(job.projectId(), id(pipeline), job.id());
            Map<String, Object> versions = new LinkedHashMap<>();
            sourceBasis(stage, job, pipeline).forEach(asset -> versions.put(asset.id(), asset.version()));
            Map<String, Object> previous = pipelines.batch(id(pipeline), stage, SOURCES);
            if (previous == null) pipelines.saveBatch(id(pipeline), stage, SOURCES, Map.of(), Map.of("versions", versions), List.of());
            else {
                long committed = pipelines.jdbc().queryForObject("SELECT COUNT(*) FROM ai_pipeline_batch WHERE pipeline_id=? AND stage=? AND source_key NOT IN (?,?)", Long.class, id(pipeline), stage, SOURCES, SUMMARY);
                if (committed > 0 && !Values.map(previous.get("versions")).equals(versions)) throw sourceChanged();
                if (committed == 0) pipelines.jdbc().update("UPDATE ai_pipeline_batch SET output_snapshot=? WHERE pipeline_id=? AND stage=? AND source_key=?", json.write(Map.of("versions", versions)), id(pipeline), stage, SOURCES);
            }
            return true;
        });
    }
    private List<Asset> sourceBasis(String stage, JobContext job, Map<String, Object> pipeline) {
        List<Asset> result = new ArrayList<>(selected(job, pipeline, "requirementIds"));
        if (stage.equals("S3")) result.addAll(selected(job, pipeline, "apiDefinitionIds"));
        if (stage.equals("S4")) {
            result.addAll(selected(job, pipeline, "databaseSourceIds"));
            tracked(job, pipeline).stream().filter(asset -> asset.type() == AssetType.API_CASE).forEach(result::add);
        }
        if (stage.equals("S5")) result.addAll(recordingAssets(job, pipeline));
        String environment = Values.text(Values.map(pipeline.get("config")), "environmentId", "");
        if (!Set.of("S1", "S2").contains(stage) && !environment.isBlank()) result.add(assets.get(job.projectId(), environment));
        return result;
    }
    private void requireBoundSources(String project, String pipeline, String stage) {
        Map<String, Object> basis = pipelines.batch(pipeline, stage, SOURCES);
        if (basis == null) throw sourceChanged();
        for (var expected : Values.map(basis.get("versions")).entrySet()) {
            try { if (!assets.get(project, expected.getKey()).version().equals(expected.getValue())) throw sourceChanged(); }
            catch (Problem error) { if (error.status() == 404) throw sourceChanged(); throw error; }
        }
    }
    private Problem sourceChanged() { return new Problem(409, "PIPELINE_SOURCE_CHANGED", "阶段来源已发生修改，已生成资产保持原状；请通过全局反馈调整已有资产，或使用新输入创建流水线"); }
    private void allowAdd(AiChangeSetService.Proposal proposal, Set<AssetType> allowed) {
        if (!"ADD".equals(proposal.operation()) || !allowed.contains(proposal.targetType()) || proposal.data() == null) throw new Problem(422, AiDraftEngine.OUT_OF_SCOPE, "生成结果超出当前阶段授权范围");
    }
    private Map<String, Object> context(String stage, Set<AssetType> types, List<Asset> sources, Map<String, Object> extra) {
        Map<String, Object> context = new LinkedHashMap<>(extra); context.put("stage", stage); context.put("sources", sources.stream().map(asset -> modelAsset(asset, extra.get("chunk"))).toList()); context.put("schemas", drafts.schemas(types)); context.put("allowedTypes", types); return context;
    }
    private Map<String, Object> modelAsset(Asset asset, Object chunk) {
        Map<String, Object> data = new LinkedHashMap<>(asset.data());
        if (asset.type() == AssetType.API_DEFINITION && data.containsKey("schema")) data.put("schema", slimApiSchema(data.get("schema")));
        if (asset.type() == AssetType.REQUIREMENT) {
            data.remove("content"); data.remove("sections"); data.remove("sourcePath");
            if (chunk instanceof DocumentParser.Section section) {
                var analysis = Values.map(data.get("analysis"));
                data.put("analysis", Values.objects(analysis.get("chunks")).stream().filter(item -> Objects.equals(item.get("index"), section.index())).toList());
            }
        }
        return Map.of("id", asset.id(), "type", asset.type(), "name", asset.name(), "version", asset.version(), "data", data);
    }
    private List<DocumentParser.Section> chunks(Asset requirement) {
        String content = Values.text(requirement.data(), "content", "");
        if (content.isBlank()) throw blocked("需求「" + requirement.name() + "」没有可分析正文");
        return documents.parse("requirement.md", content.getBytes(StandardCharsets.UTF_8)).sections();
    }
    private List<Asset> selected(JobContext job, Map<String, Object> pipeline, String key) { return strings(Values.map(pipeline.get("config")).get(key)).stream().map(id -> assets.get(job.projectId(), id)).toList(); }
    private List<Asset> recordingAssets(JobContext job, Map<String, Object> pipeline) {
        Map<String, Asset> result = new LinkedHashMap<>();
        for (Asset asset : selected(job, pipeline, "uiEvidenceIds")) {
            result.put(asset.id(), asset);
            if (asset.type() == AssetType.UI_SCENARIO) assets.children(job.projectId(), asset.id()).forEach(step -> result.put(step.id(), step));
            else if (asset.parentId() != null) { Asset parent = assets.get(job.projectId(), asset.parentId()); result.put(parent.id(), parent); }
        }
        return List.copyOf(result.values());
    }
    private List<Asset> tracked(JobContext job, Map<String, Object> pipeline) {
        List<Asset> result = new ArrayList<>();
        for (String id : strings(pipelines.get(job.projectId(), id(pipeline)).get("assetIds"))) try { result.add(assets.get(job.projectId(), id)); }
        catch (Problem missing) { if (missing.status() != 404) throw missing; }
        return result;
    }
    private void collectLocators(Map<String, Object> observation, String frame, Set<String> locators) {
        for (var element : Values.objects(observation.get("elements"))) for (String selector : strings(element.get("selectors"))) locators.add(frame + "\n" + selector);
        for (var nested : Values.objects(observation.get("frames"))) if (!nested.containsKey("unavailableReason") && !Values.text(nested, "frame", "").isBlank()) collectLocators(nested, nested.get("frame").toString(), locators);
    }
    private static String id(Map<String, Object> pipeline) { return pipeline.get("id").toString(); }
    private static String source(Map<String, Object> pipeline) { return Values.text(Values.map(pipeline.get("config")), "sourceSnapshotId", ""); }
    private static List<String> strings(Object value) { return value instanceof List<?> list ? list.stream().map(Object::toString).toList() : List.of(); }
    private String sourceKey(List<Asset> source) { return hash(json.write(source.stream().map(asset -> asset.id() + ":" + asset.version()).toList())); }
    /**
     * Holds a generated case to its documented operation: the method must match (case is normalized) and the path must
     * follow the documented template, each {param} filled with a value or ${variable} because the executor needs one there.
     * JSONPath written under "expression" is moved to "path", where the assertion engine reads it.
     */
    static AiChangeSetService.Proposal documentedRequest(AiChangeSetService.Proposal proposal, Asset definition) {
        Map<String, Object> data = new LinkedHashMap<>(proposal.data());
        String documentedMethod = Objects.toString(definition.data().get("method"), "").toUpperCase(Locale.ROOT), documentedPath = Objects.toString(definition.data().get("path"), "");
        String documented = documentedMethod + " " + documentedPath;
        String method = Objects.toString(data.get("method"), "").strip().toUpperCase(Locale.ROOT), path = Objects.toString(data.get("path"), "").strip();
        if (!method.equals(documentedMethod)) throw Problem.invalid("接口用例「" + proposal.name() + "」的方法写成了 " + (method.isEmpty() ? "空值" : method) + "，必须与接口文档 " + documented + " 相同");
        if (path.length() > 1 && path.endsWith("/") && !documentedPath.endsWith("/")) path = path.substring(0, path.length() - 1);
        String mismatch = pathMismatch(documentedPath, path);
        if (mismatch != null) throw Problem.invalid("接口用例「" + proposal.name() + "」的路径 " + (path.isEmpty() ? "为空" : path) + " 与接口文档 " + documented + " 不符：" + mismatch);
        List<Map<String, Object>> assertions = new ArrayList<>();
        for (var assertion : Values.objects(data.get("assertions"))) {
            Map<String, Object> fixed = new LinkedHashMap<>(assertion);
            if (!fixed.containsKey("type")) throw Problem.invalid("接口用例「" + proposal.name() + "」有断言缺少 type，只能是 status_code、jsonpath、header、text、response_time 之一");
            if ("jsonpath".equals(fixed.get("type")) && !fixed.containsKey("path") && !fixed.containsKey("jsonpath")) {
                Object expression = fixed.remove("expression");
                if (expression == null || expression.toString().isBlank()) throw Problem.invalid("接口用例「" + proposal.name() + "」的 jsonpath 断言缺少 path（例如 \"$.code\"）");
                fixed.put("path", expression);
            }
            assertions.add(fixed);
        }
        data.put("method", method); data.put("path", path); data.put("assertions", assertions);
        return new AiChangeSetService.Proposal(proposal.operation(), proposal.targetType(), proposal.targetId(), proposal.parentId(), proposal.localKey(), proposal.baseVersion(), proposal.name(), data);
    }
    /** Null when the generated path follows the documented template; otherwise a sentence the model can act on. */
    static String pathMismatch(String documented, String generated) {
        if (generated.isEmpty()) return "path 不能为空";
        if (generated.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) return "path 只写接口路径，不要包含协议、域名或 baseUrl";
        if (generated.contains("?")) return "查询参数请写在 queryParams，不要拼进 path";
        var unfilled = java.util.regex.Pattern.compile("(?<!\\$)\\{([^{}/]+)}").matcher(generated);
        if (unfilled.find()) return "路径参数 {" + unfilled.group(1) + "} 必须替换为具体取值或 ${变量}，花括号模板无法执行";
        String template = documented.split("\\?", 2)[0];
        if (template.length() > 1 && template.endsWith("/")) template = template.substring(0, template.length() - 1);
        StringBuilder regex = new StringBuilder(); int last = 0;
        var parameter = java.util.regex.Pattern.compile("\\$?\\{[^{}/]+}").matcher(template);
        while (parameter.find()) { regex.append(java.util.regex.Pattern.quote(template.substring(last, parameter.start()))).append("[^/]+"); last = parameter.end(); }
        regex.append(java.util.regex.Pattern.quote(template.substring(last)));
        return generated.matches(regex.toString()) ? null : "只能把 {参数} 形式的路径参数替换为具体取值或 ${变量}，其余路径段必须与文档一字不差";
    }
    /** Runs the change-set creation checks inside the repair loop, so a field or reference slip costs a repair round rather than the stage. */
    private List<AiChangeSetService.Proposal> preflight(String project, List<AiChangeSetService.Proposal> proposals) { return changes.preflight(project, proposals); }
    private static String label(AiChangeSetService.Proposal proposal) { return (proposal.targetType() == null ? "变更" : proposal.targetType().label()) + "「" + proposal.name() + "」"; }
    private static String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }
    private static Problem blocked(String reason) { return new Problem(422, "GENERATION_BLOCKED", reason); }
}
