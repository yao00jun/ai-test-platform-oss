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
    private static final int API_COVERAGE_ROUNDS = 3;
    public PipelineStageGenerator(AssetService assets, PipelineRepository pipelines, AiDraftEngine drafts, AiChangeSetService changes, AiConversationService conversations, ModelSettingsService settings, PromptCatalog prompts, JsonCodec json, DocumentParser documents, DatabaseSchemaService schemas, PageEvidenceService pages, ScenarioDependencyValidator dependencies, GenerationEvidenceService evidence,
                                  @org.springframework.beans.factory.annotation.Value("${aitest.pipeline.api-batch-size:8}") int apiBatchSize) {
        this.apiBatchSize = Math.max(1, Math.min(50, apiBatchSize));
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
                var context = context("S1", Set.of(AssetType.REQUIREMENT), List.of(requirement), Map.of("chunk", chunk, "instruction", "只分析当前需求片段的需求规则与隐性盲区。返回一项 MODIFY，仅修改此需求的 analysis，提供当前 targetId/baseVersion。analysis 必须是一个 JSON 对象（建议键：modules、roles、happyPath、branchFlows、blindSpots、openQuestions），不能是数组或字符串。"));
                var draft = generate(job, pipeline, "ba_requirement_parser", context, null, null, false);
                if (draft.changes().size() != 1) throw Problem.invalid("需求片段分析只能返回一项 analysis 修改");
                var proposal = draft.changes().getFirst();
                if (!proposal.operation().equals("MODIFY") || proposal.targetType() != AssetType.REQUIREMENT || !requirement.id().equals(proposal.targetId()) || !requirement.version().equals(proposal.baseVersion()) || proposal.data() == null || !proposal.data().keySet().equals(Set.of("analysis"))) throw Problem.invalid("需求分析超出指定片段或字段范围");
                Map<String, Object> analysis = analysisObject(proposal.data().get("analysis"));
                if (analysis.isEmpty()) throw Problem.invalid("需求分析不能为空");
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
        List<Map<String, Object>> apiIndex = definitions.stream().map(api -> Map.of("id", api.id(), "method", api.data().get("method"), "path", api.data().get("path"))).toList();
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
                        + "apiDefinitionId、method、path 必须来自该接口。将业务用例串成本批新增 SCENARIO 与独立 SCENARIO_STEP；每个 SCENARIO_STEP 都必须带 parentId，值为 \"@<本批 SCENARIO 的 localKey>\"（例如 \"@scenario_1\"），不能省略、不能为 null，也不能指向已有场景。步骤 data.targetId 用 \"@<接口用例 localKey>\" 或 existingAssets 中的真实 ID；不得向已有场景追加步骤，也不得再次创建旧批次接口用例。先提取变量再使用。";
                Map<String, Object> context = context("S3", Set.of(AssetType.API_CASE, AssetType.SCENARIO, AssetType.SCENARIO_STEP), pending, Map.of("existingAssets", prior, "apiIndex", apiIndex, "instruction", instruction));
                Set<String> covered = new LinkedHashSet<>();
                List<Asset> target = pending;
                var draft = generate(job, pipeline, "api_case_generation", context, null, null, false, changes -> {
                    List<AiChangeSetService.Proposal> owned = ownScenarioSteps(changes);
                    covered.clear();
                    for (var proposal : owned) {
                        allowAdd(proposal, Set.of(AssetType.API_CASE, AssetType.SCENARIO, AssetType.SCENARIO_STEP));
                        if (proposal.targetType() != AssetType.API_CASE) continue;
                        Asset definition = batch.stream().filter(api -> api.id().equals(proposal.data().get("apiDefinitionId"))).findFirst().orElseThrow(() -> new Problem(422, AiDraftEngine.OUT_OF_SCOPE, "接口用例「" + proposal.name() + "」的 apiDefinitionId 不属于当前文档批次"));
                        if (!definition.data().get("method").equals(proposal.data().get("method")) || !normalizePath(definition.data().get("path")).equals(normalizePath(proposal.data().get("path")))) throw new Problem(422, AiDraftEngine.OUT_OF_SCOPE, "接口用例「" + proposal.name() + "」的方法或路径与接口文档 " + definition.data().get("method") + " " + definition.data().get("path") + " 不一致");
                        covered.add(definition.id());
                    }
                    if (target.stream().noneMatch(api -> covered.contains(api.id()))) throw new Problem(422, AiDraftEngine.OUT_OF_SCOPE, "本轮没有为任何待覆盖接口生成用例：" + describe(target));
                    return owned;
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
            if (key.equals("diagnostics")) { if (stage.equals("S5")) scoped.put(key, entry.getValue()); else if (entry.getValue() instanceof List<?> list && !list.isEmpty()) scoped.put(key, Map.of("count", list.size(), "note", "静态分析告警仅在 UI 阶段提供")); continue; }
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
    static Map<String, Object> slimApiSchema(Object value) {
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
        for (Asset api : cases) {
            String key = hash(api.id() + ":" + api.version()); var cached = pipelines.batch(id(pipeline), "S4", key);
            if (cached != null) { ids.addAll(strings(cached.get("assetIds"))); checked++; continue; }
            List<Asset> basis = new ArrayList<>(sources); basis.add(api);
            Map<String, Object> context = context("S4", Set.of(AssetType.SQL_VALIDATION), basis, Map.of("databaseSchemas", structures, "targetCase", api, "instruction", "为 targetCase 生成有业务意义的后置 SQL。parentId 必须等于 targetCase.id；databaseSourceId 只能来自 databaseSchemas，没有连接时留空，生成依据 sourceEvidence.database 的待绑定草稿。只能查询真实表列并使用运行变量参数。无持久化验证需求时返回空 changes 和具体 reason。"));
            var draft = generate(job, pipeline, "agentic_pipeline_orchestration", context, null, null, true);
            for (var proposal : draft.changes()) {
                allowAdd(proposal, Set.of(AssetType.SQL_VALIDATION));
                String database = Values.text(proposal.data(), "databaseSourceId", "");
                if (!api.id().equals(proposal.parentId()) || !database.isBlank() && sources.stream().noneMatch(source -> source.id().equals(database)) || database.isBlank() && !sources.isEmpty()) throw Problem.invalid("SQL 数据源或父用例超出当前范围");
                if (!database.isBlank() && source(pipeline).isBlank()) schemas.validateReadQuery(job.projectId(), database, Values.text(proposal.data(), "sql", ""));
                if (Values.objects(proposal.data().get("assertions")).isEmpty()) throw Problem.invalid("生成 SQL 必须包含需求驱动的断言");
            }
            var output = apply(job, pipeline, "S4", key, draft.changes(), basis, context, draft.raw(), true); ids.addAll(strings(output.get("assetIds"))); checked++;
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
            observation = pages.capture(job, environment);
            if (!Objects.equals(observation.get("environmentVersion"), environmentBasis.version())) throw sourceChanged();
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
        var draft = generate(job, pipeline, "agentic_pipeline_orchestration", context, null, null, true);
        if (source(pipeline).isBlank()) UiGenerationEvidence.validate(draft.changes(), observation, recordings, configuredBase);
        Set<String> newScenarios = new HashSet<>();
        for (var proposal : draft.changes()) if (proposal.targetType() == AssetType.UI_SCENARIO && proposal.localKey() != null) newScenarios.add("@" + proposal.localKey());
        for (var proposal : draft.changes()) {
            allowAdd(proposal, Set.of(AssetType.UI_SCENARIO, AssetType.UI_STEP));
            if (proposal.targetType() != AssetType.UI_STEP) continue;
            if (!newScenarios.contains(proposal.parentId())) throw Problem.invalid("UI 步骤必须归属本批新场景");
            String frame = Values.text(proposal.data(), "frame", "");
            for (String field : List.of("selector", "targetSelector")) {
                String selector = Values.text(proposal.data(), field, "");
                if (source(pipeline).isBlank() && !selector.isBlank() && !locators.contains(frame + "\n" + selector)) throw Problem.invalid("UI 定位器没有页面或录制证据：" + selector);
            }
        }
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
        context.put("sourceEvidence", scopedEvidence(String.valueOf(context.get("stage")), evidence.context(job.projectId(), source(pipeline))));
        String conversation = pipeline.get("conversationId").toString(); conversations.recordUser(conversation, job.id(), context.get("stage") + " 自动生成", null, context);
        String stamp = "unconfigured";
        try {
            ModelSettings model = settings.current(); stamp = model.modelName() + ":" + model.version();
            var draft = drafts.generate(model, job, template, context, type, parent, type == AssetType.FUNCTIONAL_CASE ? "仅使用原模板的 featureCaseStart/featureCaseEnd Markdown 契约。当前用户消息含原始需求 chunk，请覆盖这个完整片段。" : prompts.load("pipeline_stage_contract"), allowEmpty, normalize);
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
    private static String normalizePath(Object value) { return Objects.toString(value, "").replace("${", "{"); }
    private static String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }
    private static Problem blocked(String reason) { return new Problem(422, "GENERATION_BLOCKED", reason); }
}
