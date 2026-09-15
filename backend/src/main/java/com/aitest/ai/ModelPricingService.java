package com.aitest.ai;

import com.aitest.common.Problem;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class ModelPricingService {
    private final JdbcTemplate jdbc;
    public ModelPricingService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Input(String modelName, String baseVersion, Boolean enabled, String currency, String inputPerMillion, String outputPerMillion) { }
    public record Price(String modelName, String version, boolean enabled, String currency, BigDecimal inputPerMillion, BigDecimal outputPerMillion) { }

    public Price get(String modelName) {
        String model = model(modelName);
        var rows = jdbc.query("SELECT * FROM ai_model_price WHERE model_name=?", (rs, n) -> new Price(rs.getString("model_name"), rs.getString("version"), rs.getBoolean("enabled"), rs.getString("currency"), rs.getBigDecimal("input_per_million"), rs.getBigDecimal("output_per_million")), model);
        return rows.isEmpty() ? new Price(model, "0", false, "CNY", null, null) : rows.getFirst();
    }
    @Transactional
    public Price save(Input input) {
        String model = model(input.modelName());
        if (input.baseVersion() == null || !input.baseVersion().matches("0|[1-9][0-9]{0,17}") || input.enabled() == null) throw Problem.invalid("价目需要有效 baseVersion 和 enabled");
        String currency = Objects.toString(input.currency(), "");
        try { if (!currency.matches("[A-Z]{3}")) throw new IllegalArgumentException(); Currency.getInstance(currency); }
        catch (IllegalArgumentException invalid) { throw Problem.invalid("货币需要有效 ISO 4217 三位代码，例如 CNY 或 USD"); }
        BigDecimal in = rate(input.inputPerMillion()), out = rate(input.outputPerMillion());
        long base = Long.parseLong(input.baseVersion()); Timestamp now = Timestamp.from(Instant.now());
        if (base == 0) {
            try { jdbc.update("INSERT INTO ai_model_price(model_name,version,enabled,currency,input_per_million,output_per_million,updated_at) VALUES(?,1,?,?,?,?,?)", model, input.enabled(), currency, in, out, now); }
            catch (DuplicateKeyException conflict) { throw Problem.conflict("此模型价目已设置，请重新载入后保存"); }
        } else if (jdbc.update("UPDATE ai_model_price SET version=version+1,enabled=?,currency=?,input_per_million=?,output_per_million=?,updated_at=? WHERE model_name=? AND version=?", input.enabled(), currency, in, out, now, model, base) != 1) throw Problem.conflict("此模型价目已更新，请重新载入后保存");
        return get(model);
    }
    private static String model(String name) { if (name == null || name.isBlank() || name.length() > 200) throw Problem.invalid("模型名称需要 1–200 字符"); return name.strip(); }
    private static BigDecimal rate(String text) {
        try {
            if (text == null || text.length() > 32) throw new NumberFormatException();
            BigDecimal value = new BigDecimal(text);
            if (value.signum() < 0 || value.scale() > 10 || value.compareTo(new BigDecimal("1000000000")) > 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException invalid) { throw Problem.invalid("每百万 Token 单价需要 0–1000000000 的数值，最多 10 位小数"); }
    }
}
