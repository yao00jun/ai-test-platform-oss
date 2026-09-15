package com.aitest.ai.pipeline;

import com.aitest.ai.AiChangeSetService.Proposal;
import com.aitest.asset.*;
import com.aitest.common.Problem;
import com.aitest.execution.Values;
import java.net.URI;
import java.util.*;

/** Generated navigation is limited to destinations the user configured or recorded. */
final class UiGenerationEvidence {
    static void validate(List<Proposal> proposals, Map<String, Object> observation, List<Asset> recordings, String configuredBase) {
        Set<String> knownUrls = new HashSet<>(), recordedBases = new HashSet<>(), recordedNavigation = new HashSet<>();
        addUrl(knownUrls, configuredBase, "");
        collectObservedUrls(observation, knownUrls);
        Map<String, String> parents = new HashMap<>();
        for (Asset asset : recordings) if (asset.type() == AssetType.UI_SCENARIO) {
            String base = Values.text(asset.data(), "baseUrl", configuredBase);
            parents.put(asset.id(), base); recordedBases.add(base); addUrl(knownUrls, base, "");
        }
        for (Asset asset : recordings) if (asset.type() == AssetType.UI_STEP && "navigate".equals(asset.data().get("action"))) {
            String base = parents.getOrDefault(asset.parentId(), configuredBase), url = Values.text(asset.data(), "url", "");
            recordedNavigation.add(base + "\n" + url); addUrl(knownUrls, url, base);
        }
        Map<String, String> generated = new HashMap<>();
        for (Proposal proposal : proposals) if (proposal.targetType() == AssetType.UI_SCENARIO) {
            String base = Values.text(proposal.data(), "baseUrl", configuredBase);
            if (!base.isBlank() && !recordedBases.contains(base) && !knownUrls.contains(normalized(base, ""))) throw unsupported();
            generated.put("@" + proposal.localKey(), base);
        }
        for (Proposal proposal : proposals) if (proposal.targetType() == AssetType.UI_STEP) {
            if (!generated.containsKey(proposal.parentId())) throw Problem.invalid("生成的 UI 步骤必须归属本批新增场景，不能改写已有录制资产");
            if (!"navigate".equals(proposal.data().get("action"))) continue;
            String base = generated.get(proposal.parentId()), url = Values.text(proposal.data(), "url", "");
            if (!recordedNavigation.contains(base + "\n" + url) && !knownUrls.contains(normalized(url, base))) throw unsupported();
        }
    }
    private static void collectObservedUrls(Map<String, Object> observation, Set<String> urls) {
        addUrl(urls, Values.text(observation, "url", ""), "");
        for (var frame : Values.objects(observation.get("frames"))) if (!frame.containsKey("unavailableReason")) collectObservedUrls(frame, urls);
    }
    private static void addUrl(Set<String> urls, String url, String base) { String value = normalized(url, base); if (value != null) urls.add(value); }
    private static String normalized(String value, String base) {
        if (value.isBlank()) return null;
        try {
            URI uri = URI.create(value);
            if (!uri.isAbsolute()) { if (base.isBlank()) return null; uri = URI.create(base).resolve(uri); }
            if (!Set.of("http", "https").contains(Objects.toString(uri.getScheme(), "").toLowerCase(Locale.ROOT)) || uri.getHost() == null || uri.getUserInfo() != null) return null;
            String path = uri.getRawPath();
            return (uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getRawAuthority().toLowerCase(Locale.ROOT)
                    + (path == null || path.isEmpty() ? "/" : path) + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())
                    + (uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment()));
        } catch (IllegalArgumentException invalid) { return null; }
    }
    private static Problem unsupported() { return Problem.invalid("AI 生成的页面地址没有页面采集或人工录制证据，请补充对应页面证据"); }
    private UiGenerationEvidence() { }
}
