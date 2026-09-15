package com.aitest.ai;

import com.aitest.asset.Asset;
import com.aitest.asset.AssetRepository;
import com.aitest.asset.AssetService;
import com.aitest.asset.AssetType;
import com.aitest.job.JobService;
import com.aitest.support.ModelFixtureServer;
import com.aitest.support.MySqlIntegrationTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doAnswer;

/** R12: a manual consumer committed at the final project-lock handoff must be visible. */
@TestPropertySource(properties = {
        "spring.datasource.hikari.transaction-isolation=TRANSACTION_REPEATABLE_READ",
        "aitest.schedules.enabled=false", "aitest.morning-brief.enabled=false", "aitest.notifications.enabled=false"
})
class LocalRefinementConcurrencyIT extends MySqlIntegrationTest {
    @Autowired AssetService assets;
    @Autowired AiRefinementService refinement;
    @Autowired AiConversationService conversations;
    @Autowired ModelSettingsService settings;
    @Autowired JobService jobs;
    @Autowired TransactionTemplate transactions;
    @MockitoSpyBean AssetRepository repository;

    @ParameterizedTest
    @EnumSource(value = AssetType.class, names = {"API_CASE", "SQL_VALIDATION", "UI_STEP"})
    void concurrentManualConsumerPreventsLocalExporterRename(AssetType type) throws Exception {
        String project = assets.createProject("Concurrent dependency " + UUID.randomUUID(), Map.of()).id();
        Asset producer = producer(project, type);
        Asset consumer = assets.create(project, AssetType.API_CASE, null, "人工消费者", Map.of("path", "/orders"), "MANUAL");
        List<Asset> originals = assets.all(project);
        var producerHistory = assets.history(project, producer.id());
        CountDownLatch manualUpdated = new CountDownLatch(1), commitManual = new CountDownLatch(1), adoptionReachedLock = new CountDownLatch(1);
        try (var model = new ModelFixtureServer(); var manualWriter = Executors.newVirtualThreadPerTaskExecutor()) {
            settings.save(new ModelSettingsService.Input(model.url(), "concurrency-fixture", "fixture", 0.1, 30));
            var candidate = model.hold(rename(type));
            var submitted = refinement.submit(new AiRefinementService.RefineRequest(project, type, producer.id(), producer.version(),
                    null, "重命名导出变量", List.of(exportField(type)), "REPLACE_ON_SUCCESS", UUID.randomUUID().toString()));
            try {
                assertThat(candidate.entered().await(10, TimeUnit.SECONDS)).as("model generation started").isTrue();
                var manual = manualWriter.submit(() -> transactions.execute(tx -> {
                    Asset updated = assets.update(project, consumer.id(), consumer.version(), null,
                            Map.of("headers", Map.of("X-Session", "${token}")), null, "MANUAL");
                    manualUpdated.countDown();
                    awaitBarrier(commitManual);
                    return updated;
                }));
                assertThat(manualUpdated.await(10, TimeUnit.SECONDS)).as("manual consumer updated under project lock").isTrue();
                // Keep the real database lock. The spy only observes when final adoption tries to acquire it.
                doAnswer(invocation -> {
                    adoptionReachedLock.countDown();
                    return invocation.callRealMethod();
                }).when(repository).lockProject(project);
                candidate.release().countDown();
                assertThat(adoptionReachedLock.await(10, TimeUnit.SECONDS)).as("AI adoption reached the held project lock").isTrue();
                commitManual.countDown();
                Asset human = manual.get(10, TimeUnit.SECONDS);
                await().atMost(Duration.ofSeconds(20)).until(() -> jobs.get(project, submitted.jobId()).terminal());

                assertThat(jobs.get(project, submitted.jobId()).status()).as("renaming an export now used by the committed manual consumer").isEqualTo("FAILED");
                assertThat(conversations.messages(project, submitted.conversationId()).getLast()).containsEntry("status", "CONFLICT");
                assertThat(assets.get(project, producer.id())).isEqualTo(producer);
                assertThat(assets.history(project, producer.id())).isEqualTo(producerHistory);
                assertThat(assets.get(project, consumer.id())).isEqualTo(human);
                assertThat(assets.all(project)).isEqualTo(originals.stream().map(asset -> asset.id().equals(consumer.id()) ? human : asset).toList());
            } finally {
                candidate.release().countDown();
                commitManual.countDown();
            }
        }
    }

    private Asset producer(String project, AssetType type) {
        return switch (type) {
            case API_CASE -> assets.create(project, type, null, "登录提取", Map.of("path", "/login", "extractors", List.of(Map.of("variable", "token", "jsonpath", "$.token"))), "MANUAL");
            case SQL_VALIDATION -> assets.create(project, type, null, "SQL 导出", Map.of("sql", "SELECT 1 AS token", "exports", Map.of("token", "token")), "MANUAL");
            case UI_STEP -> {
                Asset scenario = assets.create(project, AssetType.UI_SCENARIO, null, "UI 提取场景", Map.of(), "MANUAL");
                yield assets.create(project, type, scenario.id(), "UI 导出", Map.of("action", "extract", "selector", "#session", "saveAs", "token"), "MANUAL");
            }
            default -> throw new IllegalArgumentException("Unsupported producer");
        };
    }

    private String exportField(AssetType type) {
        return switch (type) {
            case API_CASE -> "extractors";
            case SQL_VALIDATION -> "exports";
            case UI_STEP -> "saveAs";
            default -> throw new IllegalArgumentException("Unsupported producer");
        };
    }

    private String rename(AssetType type) {
        return switch (type) {
            case API_CASE -> "{\"data\":{\"extractors\":[{\"variable\":\"renamed\",\"jsonpath\":\"$.token\"}]}}";
            case SQL_VALIDATION -> "{\"data\":{\"exports\":{\"renamed\":\"token\"}}}";
            case UI_STEP -> "{\"data\":{\"saveAs\":\"renamed\"}}";
            default -> throw new IllegalArgumentException("Unsupported producer");
        };
    }

    private static void awaitBarrier(CountDownLatch barrier) {
        try {
            if (!barrier.await(15, TimeUnit.SECONDS)) throw new AssertionError("Manual commit barrier was not released");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
