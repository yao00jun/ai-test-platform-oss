package com.aitest.analysis.source;

import com.aitest.common.Problem;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.Set;

/** Validates locations without touching the repository or interpreting shell text. */
public final class SourcePaths {
    private SourcePaths() { }
    public static void validate(String value, boolean repository) {
        if (value == null || value.isBlank()) return;
        if (value.length() > 2000 || value.chars().anyMatch(c -> c < 32)) throw Problem.invalid("源码路径过长或包含控制字符");
        if (remote(value)) {
            if (!repository) throw Problem.invalid("SQL 脚本需要本地绝对路径，或直接粘贴 DDL");
            try {
                URI uri = URI.create(value);
                if (!Set.of("https", "http").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) throw new IllegalArgumentException();
            } catch (IllegalArgumentException invalid) { throw Problem.invalid("Git 地址须为不含凭证、查询参数或片段的 HTTP(S) URL；私有仓库可先在本地检出"); }
        } else try {
            if (!Path.of(value).isAbsolute()) throw Problem.invalid("源码和 SQL 脚本需要本地绝对路径");
        } catch (InvalidPathException invalid) { throw Problem.invalid("源码路径格式无效"); }
    }
    public static boolean remote(String value) { return value != null && value.matches("^[A-Za-z][A-Za-z0-9+.-]*://.*"); }
    public static void validateRef(String value) {
        if (value == null || value.isBlank()) return;
        if (value.length() > 200 || !value.matches("[\\p{L}\\p{N}_][\\p{L}\\p{N}_./@+\\-]*(?:[~^][0-9]*)*") || value.contains("..") || value.endsWith(".lock")) throw Problem.invalid("Git 版本需要提交哈希、分支或标签，可附带 ~ / ^ 祖先选择器");
    }
    public static Path readable(String value, String roots) throws IOException {
        validate(value, false);
        Path real = Path.of(value).toRealPath();
        boolean allowed = roots.isBlank();
        for (String root : roots.split(";")) if (!root.isBlank() && real.startsWith(Path.of(root.strip()).toRealPath())) allowed = true;
        if (!allowed) throw new Problem(403, "PATH_NOT_ALLOWED", "源码不在配置的可读目录内");
        return real;
    }
}
