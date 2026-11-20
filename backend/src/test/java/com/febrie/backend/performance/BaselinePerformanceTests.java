package com.febrie.backend.performance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实应用负责种子、会话与对账，发压留给独立 k6 进程。 */
@EnabledIfEnvironmentVariable(named = "IT_BASELINE", matches = "Smoke|Standard|Exploration")
class BaselinePerformanceTests {
    @Test void measure() throws Exception {
        String mode = System.getenv("IT_BASELINE");
        boolean smoke = mode.equals("Smoke");
        Path root = Path.of(System.getenv("IT_BASELINE_OUTPUT"));
        Path script = Path.of(System.getenv("IT_BASELINE_SCRIPT"));
        var size = smoke ? BaselineData.Size.SMOKE : BaselineData.Size.STANDARD;
        var stages = BaselineK6.stages(mode, System.getenv("IT_BASELINE_SCENARIO"));
        var workload = BaselineData.workload(size);
        var results = new ArrayList<BaselineReport.Stage>();
        var json = new ObjectMapper();
        var started = Instant.now();
        boolean complete = false;
        String failure = null;
        assertTrue(!stages.isEmpty(), "需要至少一个压测场景");
        try (var environment = new BaselineEnvironment(size)) {
            environment.seed();
            var seed = new LinkedHashMap<String, Object>();
            seed.put("seed", BaselineData.SEED); seed.put("users", size.users()); seed.put("articles", size.articles());
            seed.put("likeRelations", size.likes()); seed.put("database", environment.database); seed.put("index", environment.index);
            seed.put("seedSeconds", Duration.between(started, Instant.now()).toSeconds());
            seed.put("queries", workload.queries()); seed.put("rankScheduling", false);
            seed.put("searchPageSize", smoke ? 5 : 20);
            json.writeValue(root.resolve("seed.json").toFile(), seed);
            for (int round = 1; round <= (smoke ? 1 : 3); round++) {
                for (var stage : stages) {
                    Path output = Files.createDirectories(root.resolve("round-" + round).resolve(stage.directory()));
                    System.out.println("基线第 " + round + " 轮：" + stage.directory());
                    BaselineReport.Stage outcome;
                    try (var site = environment.stage(stage)) {
                        outcome = runStage(round, stage, site, script, output, workload, smoke);
                    } catch (Exception error) {
                        outcome = BaselineReport.Stage.failed(round, stage, error.getMessage());
                        System.err.println("基线阶段失败 " + stage.directory() + "：" + error);
                        error.printStackTrace(System.err);
                    }
                    results.add(outcome);
                    BaselineReport.write(root, mode, results, false, null);
                }
            }
            complete = true;
        } catch (Exception | LinkageError | AssertionError error) {
            failure = error.getMessage();
            throw error;
        } finally { BaselineReport.write(root, mode, results, complete, failure); }
        assertTrue(results.stream().allMatch(BaselineReport.Stage::passed), "存在未通过的阶段，请检查 report.md");
    }

    private BaselineReport.Stage runStage(int round, BaselineK6.Stage stage, BaselineEnvironment.Site site,
                                         Path script, Path output, BaselineData.Workload workload, boolean smoke) throws Exception {
        Path input = Files.createTempFile("ec-k6-session-", ".json");
        try {
            var sessions = stage.scenario().equals("search") ? List.of() : site.sessions(stage.vus());
            site.json.writeValue(input.toFile(), Map.of("base", site.base, "seed", workload.seed(),
                    "articles", workload.articles(), "partitions", workload.partitions(), "queries", workload.queries(),
                    "sessions", sessions, "pageSize", smoke ? 5 : 20));
            return BaselineStage.run(round, stage, site, script, input, output, smoke);
        } finally { Files.deleteIfExists(input); }
    }
}
