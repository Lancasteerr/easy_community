package com.febrie.backend.performance;

import com.febrie.backend.article.comment.infrastructure.search.BaselineCommentFixture;
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
        int rounds = BaselineK6.rounds(mode, System.getenv("IT_BASELINE_ROUNDS"));
        var stages = BaselineK6.stages(mode, System.getenv("IT_BASELINE_SCENARIO"),
                BaselineK6.rates(System.getenv("IT_BASELINE_RPS")));
        var baseSize = smoke ? BaselineData.Size.SMOKE : BaselineData.Size.STANDARD;
        // 自定义到达率可能需要更多 VU，会话槽与测试账号一一对应。
        int accounts = stages.stream().filter(BaselineK6.Stage::needsSessions).mapToInt(BaselineK6.Stage::vus)
                .max().orElse(0);
        var size = new BaselineData.Size(Math.max(accounts, baseSize.users()), baseSize.articles(), baseSize.likesPerArticle());
        var plan = new BaselineReport.Plan(rounds, stages);
        var workload = BaselineData.workload(size);
        var results = new ArrayList<BaselineReport.Stage>();
        var json = new ObjectMapper();
        var started = Instant.now();
        boolean complete = false;
        String failure = null;
        assertTrue(!stages.isEmpty(), "需要至少一个压测场景");
        boolean needsCommentSeeds = stages.stream().anyMatch(stage -> stage.readsComments() || stage.writesReplies());
        try (var environment = new BaselineEnvironment(size, needsCommentSeeds)) {
            environment.seed();
            var seed = new LinkedHashMap<String, Object>();
            seed.put("seed", BaselineData.SEED); seed.put("users", size.users()); seed.put("articles", size.articles());
            seed.put("likeRelations", size.likes()); seed.put("database", environment.database); seed.put("index", environment.index);
            seed.put("seedSeconds", Duration.between(started, Instant.now()).toSeconds());
            seed.put("queries", workload.queries()); seed.put("rankScheduling", false);
            seed.put("searchPageSize", smoke ? 5 : 20);
            if (stages.stream().anyMatch(BaselineK6.Stage::comments)) {
                seed.put("commentReadArticles", environment.commentReadArticles);
                seed.put("commentRootsPerReadArticle", BaselineCommentFixture.ROOTS_PER_ARTICLE);
                seed.put("commentSeedRows", environment.commentSeedMax);
                seed.put("commentWriteFirst", environment.commentWriteFirst);
                seed.put("commentWriteArticles", BaselineEnvironment.COMMENT_WRITE_ARTICLES);
            }
            json.writeValue(root.resolve("seed.json").toFile(), seed);
            for (int round = 1; round <= rounds; round++) {
                for (var stage : stages) {
                    Path output = Files.createDirectories(root.resolve("round-" + round).resolve(stage.directory()));
                    System.out.println("基线第 " + round + " 轮：" + stage.directory());
                    BaselineReport.Stage outcome;
                    try (var site = environment.stage(stage)) {
                        outcome = runStage(round, stage, site, script, output, workload, smoke, environment);
                    } catch (Exception error) {
                        outcome = BaselineReport.Stage.failed(round, stage, error.getMessage());
                        System.err.println("基线阶段失败 " + stage.directory() + "：" + error);
                        error.printStackTrace(System.err);
                    }
                    results.add(outcome);
                    BaselineReport.write(root, mode, plan, results, false, null);
                }
            }
            complete = true;
        } catch (Exception | LinkageError | AssertionError error) {
            failure = error.getMessage();
            throw error;
        } finally { BaselineReport.write(root, mode, plan, results, complete, failure); }
        assertTrue(results.stream().allMatch(BaselineReport.Stage::passed), "存在未通过的阶段，请检查 report.md");
    }

    private BaselineReport.Stage runStage(int round, BaselineK6.Stage stage, BaselineEnvironment.Site site,
                                         Path script, Path output, BaselineData.Workload workload, boolean smoke,
                                         BaselineEnvironment environment) throws Exception {
        Path input = Files.createTempFile("ec-k6-session-", ".json");
        try {
            var sessions = stage.needsSessions() ? site.sessions(stage.vus()) : List.of();
            site.json.writeValue(input.toFile(), Map.ofEntries(
                    Map.entry("base", site.base), Map.entry("seed", workload.seed()),
                    Map.entry("articles", workload.articles()), Map.entry("partitions", workload.partitions()),
                    Map.entry("queries", workload.queries()), Map.entry("sessions", sessions),
                    Map.entry("pageSize", smoke ? 5 : 20), Map.entry("commentReadArticles", environment.commentReadArticles),
                    Map.entry("commentRootsPerArticle", BaselineCommentFixture.ROOTS_PER_ARTICLE),
                    Map.entry("commentWriteFirst", environment.commentWriteFirst),
                    Map.entry("commentWriteArticles", BaselineEnvironment.COMMENT_WRITE_ARTICLES)));
            return BaselineStage.run(round, stage, site, script, input, output, smoke);
        } finally { Files.deleteIfExists(input); }
    }
}
