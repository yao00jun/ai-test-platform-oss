package com.aitest.exchange.codegen;

import com.aitest.asset.AssetType;
import com.aitest.exchange.*;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public final class CodegenImportCodec implements ImportCodec {
    public Set<AssetType> assetTypes() { return Set.of(AssetType.UI_SCENARIO, AssetType.UI_STEP); }
    public Set<String> formats() { return Set.of("java", "js", "ts"); }
    public ParsedExchange parse(String source, String format, byte[] bytes, AssetType type) {
        if (bytes.length > 1024 * 1024) return ParsedExchange.invalid(new ExchangeIssue(source, 1, "$script", "单个录制脚本最多 1 MB，请按场景拆分"));
        String text = ExchangeIO.utf8(bytes, source);
        List<ExchangeIssue> errors = new ArrayList<>();
        List<ExchangeIssue> warnings = new ArrayList<>();
        var statements = format.equals("java") ? new JavaRecorderParser(source, errors).parse(text) : new JavascriptRecorderParser(source, errors).parse(text);
        return new RecorderSemantics(source, errors, warnings).convert(statements, type, format);
    }
}
