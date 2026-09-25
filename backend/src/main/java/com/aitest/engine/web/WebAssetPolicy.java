package com.aitest.engine.web;

import com.aitest.asset.*;
import com.aitest.common.Problem;
import com.aitest.execution.ExecutionLimits;
import com.aitest.execution.Values;
import com.aitest.storage.FileStorageService;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public final class WebAssetPolicy implements AssetPolicy {
    private static final Set<String> LOCATOR_ACTIONS = Set.of("click", "dblclick", "fill", "press", "select", "selectOption", "check", "uncheck", "hover", "dragAndDrop", "upload", "assertText", "assertVisible", "assertHidden", "assertValue", "assertCount", "extract", "popup", "download");
    private final FileStorageService files;
    public WebAssetPolicy(FileStorageService files) { this.files = files; }
    public void validate(Asset previous, Asset candidate) {
        var data = candidate.data();
        if (candidate.type() == AssetType.UI_SCENARIO) {
            Values.integer(data, "viewportWidth", 1440, 320, 7680); Values.integer(data, "viewportHeight", 900, 200, 4320);
            Values.integer(data, "timeoutMs", 120000, ExecutionLimits.UI_SCENARIO_TIMEOUT_MIN_MS, ExecutionLimits.UI_SCENARIO_TIMEOUT_MAX_MS); return;
        }
        if (candidate.type() != AssetType.UI_STEP) return;
        String action = Values.text(data, "action", "");
        Values.integer(data, "timeoutMs", 15000, ExecutionLimits.UI_STEP_TIMEOUT_MIN_MS, ExecutionLimits.UI_STEP_TIMEOUT_MAX_MS);
        if (LOCATOR_ACTIONS.contains(action)) WebLocator.parse(Values.text(data, "selector", ""));
        if (action.equals("dragAndDrop")) WebLocator.parse(Values.text(data, "targetSelector", ""));
        if (Set.of("extract", "popup").contains(action) && !Values.text(data, "saveAs", "").matches("[A-Za-z_][A-Za-z0-9_.-]{0,127}")) throw Problem.invalid("提取或弹窗动作需要合法的保存名称");
        if (action.equals("assertCount")) {
            String expected = Values.text(data, "expected", "");
            if (!expected.contains("${")) try { if (Integer.parseInt(expected) < 0) throw new NumberFormatException(); }
            catch (NumberFormatException invalid) { throw Problem.invalid("数量断言的预期值必须为非负整数"); }
        }
        if (action.equals("upload")) {
            List<?> ids = (List<?>) data.get("fileIds");
            if (ids.isEmpty() || ids.size() > 8) throw Problem.invalid("上传动作需要 1–8 个受管附件");
            long total = 0;
            for (Object id : ids) {
                if (!(id instanceof String text)) throw Problem.invalid("附件 ID 必须是文本");
                total += files.get(candidate.projectId(), text).size();
            }
            if (total > 64 * 1024 * 1024) throw Problem.invalid("单次上传附件总大小最多 64 MB");
        }
    }
}
