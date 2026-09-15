package com.aitest.analysis;

import com.aitest.analysis.impact.SourceImpactRepository;
import com.aitest.asset.*;
import com.aitest.common.Problem;
import com.aitest.execution.Values;
import org.springframework.stereotype.Service;
import java.util.*;

/** Source identifiers belong to immutable evidence repositories, not to the editable asset reference graph. */
@Service
public final class SourceBindingService implements AssetPolicy {
    private final SourceSnapshotRepository sources; private final SourceImpactRepository impacts;
    public SourceBindingService(SourceSnapshotRepository sources, SourceImpactRepository impacts) { this.sources = sources; this.impacts = impacts; }
    @Override public void validate(Asset previous, Asset candidate) {
        if (candidate.data().containsKey("sourceSnapshotId") || candidate.data().containsKey("impactId")) binding(candidate);
    }
    public List<Map<String, Object>> capture(Collection<Asset> assets) {
        Map<String, Map<String, Object>> bindings = new LinkedHashMap<>();
        for (Asset asset : assets) {
            var binding = binding(asset);
            if (!binding.isEmpty()) bindings.put(binding.get("sourceSnapshotId") + ":" + binding.get("impactId"), binding);
        }
        return List.copyOf(bindings.values());
    }
    private Map<String, Object> binding(Asset asset) {
        String source = Values.text(asset.data(), "sourceSnapshotId", ""), impact = Values.text(asset.data(), "impactId", "");
        if (source.isBlank()) { if (!impact.isBlank()) throw Problem.invalid("影响报告需要关联其源码快照"); return Map.of(); }
        var binding = new LinkedHashMap<>(sources.binding(asset.projectId(), source)); binding.put("impactId", impact);
        if (!impact.isBlank()) {
            var report = impacts.metadata(asset.projectId(), impact);
            if (!source.equals(report.get("sourceSnapshotId"))) throw Problem.invalid("影响报告与源码快照不匹配");
            if (!"READY".equals(report.get("status"))) throw new Problem(409, "SOURCE_IMPACT_NOT_READY", "影响报告尚未完成");
            binding.put("baselineSnapshotId", Objects.toString(report.get("baselineSnapshotId"), ""));
        }
        return binding;
    }
}
