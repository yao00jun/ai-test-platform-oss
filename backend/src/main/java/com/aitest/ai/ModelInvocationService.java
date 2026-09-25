package com.aitest.ai;

import com.aitest.common.*;
import com.aitest.job.JobContext;
import com.aitest.job.JobService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** A logical gateway invocation may contain bounded HTTP compatibility/rate-limit attempts. */
@Service
public class ModelInvocationService {
    private final CompanyModelGateway gateway;
    private final ModelPricingService prices;
    private final PromptCatalog prompts;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate records;
    public ModelInvocationService(CompanyModelGateway gateway, ModelPricingService prices, PromptCatalog prompts, JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.gateway = gateway; this.prices = prices; this.prompts = prompts; this.jdbc = jdbc;
        records = new TransactionTemplate(transactions); records.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public String complete(ModelSettings model, JobContext job, String template, String purpose, String system, String user, Consumer<String> onToken) {
        job.checkpoint();
        var price = prices.get(model.modelName());
        String id = Ids.newId(); Instant at = Instant.now(); long started = System.nanoTime();
        records.executeWithoutResult(tx -> jdbc.update("INSERT INTO ai_model_invocation(id,project_id,job_id,model_name,model_version,template_name,template_version,purpose,status,started_at,pricing_version,currency,input_per_million,output_per_million) VALUES(?,?,?,?,?,?,?,?,'RUNNING',?,?,?,?,?)",
                id, job.projectId(), job.id(), model.modelName(), model.version(), template, prompts.version(template), purpose, Timestamp.from(at), price.enabled() ? Long.valueOf(price.version()) : null, price.enabled() ? price.currency() : null, price.enabled() ? price.inputPerMillion() : null, price.enabled() ? price.outputPerMillion() : null));
        var telemetry = new ModelCallTelemetry(); String status = "SUCCEEDED", errorCode = null;
        RuntimeException original = null;
        TokenSlices tokens = new TokenSlices(onToken);
        try {
            String result = gateway.complete(model, system, user, tokens, throttled(job::checkpoint), telemetry, job::status);
            tokens.flush();
            return result;
        }
        catch (RuntimeException failure) {
            original = failure;
            try { tokens.flush(); } catch (RuntimeException ignored) { /* A cancelled job no longer accepts events. */ }
            status = failure instanceof CancellationException ? "CANCELLED" : failure instanceof JobService.LeaseLostException ? "INTERRUPTED" : "FAILED";
            errorCode = failure instanceof Problem problem ? problem.code() : status;
            throw failure;
        } finally {
            boolean interrupted = Thread.interrupted();
            String finalStatus = status, finalError = errorCode;
            BigDecimal cost = price.enabled() && telemetry.promptTokens() != null && telemetry.completionTokens() != null
                    ? price.inputPerMillion().multiply(BigDecimal.valueOf(telemetry.promptTokens())).add(price.outputPerMillion().multiply(BigDecimal.valueOf(telemetry.completionTokens()))).divide(BigDecimal.valueOf(1_000_000), 12, RoundingMode.HALF_UP) : null;
            try {
                records.executeWithoutResult(tx -> jdbc.update("UPDATE ai_model_invocation SET status=?,error_code=?,completed_at=?,duration_ms=?,http_attempts=?,usage_reported=?,prompt_tokens=?,completion_tokens=?,total_tokens=?,response_model=?,estimated_cost=? WHERE id=? AND status='RUNNING'",
                        finalStatus, finalError, Timestamp.from(Instant.now()), (System.nanoTime() - started) / 1_000_000, telemetry.httpAttempts(), telemetry.usageReported(), telemetry.promptTokens(), telemetry.completionTokens(), telemetry.totalTokens(), telemetry.responseModel(), cost, id));
            } catch (RuntimeException recordingFailure) { if (original != null) original.addSuppressed(recordingFailure); else throw recordingFailure; }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }

    /** The pump checks between tokens and every 100 ms; reading the job row at most every 200 ms keeps cancellation prompt without a query per token. */
    static Runnable throttled(Runnable checkpoint) {
        long[] last = {System.nanoTime() - TimeUnit.SECONDS.toNanos(1)};
        return () -> {
            long now = System.nanoTime();
            if (now - last[0] < TimeUnit.MILLISECONDS.toNanos(200)) return;
            checkpoint.run(); last[0] = now;
        };
    }

    /** Streams to the job in quarter-second slices: one event row per slice instead of one per token. */
    static final class TokenSlices implements Consumer<String> {
        private final Consumer<String> target;
        private final StringBuilder pending = new StringBuilder();
        private long flushedAt = System.nanoTime();
        TokenSlices(Consumer<String> target) { this.target = target; }
        @Override public void accept(String token) {
            pending.append(token);
            if (pending.length() >= 4000 || System.nanoTime() - flushedAt >= TimeUnit.MILLISECONDS.toNanos(250)) flush();
        }
        void flush() {
            flushedAt = System.nanoTime();
            if (pending.isEmpty()) return;
            String slice = pending.toString(); pending.setLength(0);
            target.accept(slice);
        }
    }
}
