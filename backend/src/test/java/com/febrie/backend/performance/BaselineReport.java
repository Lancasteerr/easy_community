package com.febrie.backend.performance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/** 同一份汇总生成机器可读文件和人读报告，失效与部分成功也写入索引。 */
final class BaselineReport {
    record Plan(int rounds, List<BaselineK6.Stage> stages) { }

    record Stage(int round, String scenario, int load, String model, boolean passed, List<String> issues,
                 Map<String, BaselineStats.Route> routes, BaselineStats.K6 k6, int configuredVus, int inFlight,
                 int likeInFlight, long backlogPeak, boolean backlogGrowing, Long rowLockWaits, Long rowLockMs,
                 Long sqlCoverageMs, Long mqDrainMs, BaselineReconcile.Audit likeAudit, BaselineReconcile.Pv pvAudit,
                 Map<String, Object> diagnostics) {
        static Stage failed(int round, BaselineK6.Stage stage, String reason) {
            return new Stage(round, stage.scenario(), stage.load(), stage.model().label, false,
                    List.of(reason == null ? "执行失败" : reason), Map.of(), null, stage.vus(), 0, 0, 0, false,
                    null, null, null, null, null, null, Map.of());
        }
    }

    private BaselineReport() { }

    static void write(Path root, String mode, Plan plan, List<Stage> stages, boolean complete, String failure)
            throws IOException {
        var json = new ObjectMapper();
        json.writerWithDefaultPrettyPrinter().writeValue(root.resolve("summary.json").toFile(),
                Map.of("mode", mode, "plan", plan, "complete", complete, "failure", failure == null ? "" : failure,
                        "stages", stages));
        var report = new StringBuilder("# k6 压测报告\n\n");
        report.append("模式 **").append(mode).append("** · ").append(complete ? "运行结束" : "部分结果").append("\n\n");
        report.append("计划 ").append(plan.rounds()).append(" 轮，每轮 ").append(plan.stages().size())
                .append(" 档，已记录 ").append(stages.size()).append(" 档。\n\n");
        if (failure != null) { report.append("运行错误：").append(failure.replace('\n', ' ')).append("\n\n"); }
        report.append("""
                环境见 [metadata.json](metadata.json)，数据见 [seed.json](seed.json)，结构化汇总见 [summary.json](summary.json)。
                HTTP 延迟为客户端请求开始至响应完成的耗时，含连接开销。成功 RPS 使用计划窗口与末尾在途请求的实际完成窗口两者中的较长者。
                行锁统计为独立 MySQL 实例差值，MQ 采用管理接口异步队列统计。

                | 轮次／场景 | 目标 | 接口 | 成功 RPS | P95/P99 ms | 429 | 积压峰值／趋势 | SQL 锁等待次／ms | 状态 |
                |---|---:|---|---:|---:|---:|---|---|---|
                """);
        for (var stage : stages) {
            if (stage.routes().isEmpty()) { row(report, stage, "—", null); }
            else { stage.routes().forEach((name, route) -> row(report, stage, name, route)); }
        }
        for (var stage : stages) {
            report.append("\n### 第 ").append(stage.round()).append(" 轮 · ").append(stage.scenario())
                    .append(" · ").append(stage.load()).append(' ').append(stage.model()).append("\n\n");
            report.append("- VU 上限 ").append(stage.configuredVus()).append("，在途峰值 ").append(stage.inFlight());
            if (stage.k6() != null) {
                report.append("，观测 VU 峰值 ").append(stage.k6().peakVus()).append("，丢弃迭代 ").append(stage.k6().dropped());
                if (stage.k6().journeys() > 0) {
                    report.append("，完整旅程 ").append(stage.k6().successfulJourneys()).append('/').append(stage.k6().journeys());
                }
            }
            report.append("。\n");
            if (stage.diagnostics().containsKey("journeyStartsPerSecond")) {
                report.append(String.format(Locale.ROOT, "- 实际启动 %.2f 旅程/秒，合计 %.2f HTTP 请求/秒。%n",
                        stage.diagnostics().get("journeyStartsPerSecond"), stage.diagnostics().get("httpRequestsPerSecond")));
            }
            if (stage.likeAudit() != null) {
                report.append("- 点赞并发账号峰值 ").append(stage.likeInFlight()).append("，HTTP 受理 ")
                        .append(stage.likeAudit().accepted()).append("，最终关系 ")
                        .append(stage.likeAudit().relations()).append("，关系差异 ").append(stage.likeAudit().mismatched())
                        .append("，计数差异 ").append(stage.likeAudit().wrongCounters())
                        .append("，SQL 覆盖观察 ").append(format(stage.sqlCoverageMs())).append(" ms")
                        .append("，MQ 排空观察 ").append(format(stage.mqDrainMs())).append(" ms。\n");
            }
            if (stage.pvAudit() != null) {
                report.append("- 详情 HTTP 200 ").append(stage.pvAudit().httpResponses())
                        .append("，自然刷盘已观察到 ").append(stage.pvAudit().sqlObservedBeforeDrain())
                        .append("，SQL 总量／日量 ").append(stage.pvAudit().sqlTotal()).append('/')
                        .append(stage.pvAudit().sqlDaily()).append("。\n");
            }
            if (stage.diagnostics().get("commentEs") instanceof Map<?, ?> es) {
                report.append("- 评论 ES 开放搜索上下文峰值 ").append(es.get("openContextsPeak"))
                        .append("，ES 堆使用峰值 ").append(es.get("heapUsedBytesPeak"))
                        .append(" 字节，测量期搜索拒绝增量 ").append(es.get("searchRejected")).append("。\n");
            }
            if (stage.diagnostics().get("commentWrite") instanceof BaselineCommentWrite.Result result) {
                commentWrite(report, result);
            }
            if (stage.diagnostics().get("commentReplyWrite") instanceof BaselineCommentReplyWrite.Audit audit) {
                report.append("- 含预热确认回复 ").append(audit.successful()).append(" 笔，落库匹配 ").append(audit.matched())
                        .append(" 笔，额外或错误结果 ").append(audit.unexpectedResults())
                        .append("，额外或错误回复行 ").append(audit.unexpectedReplyRows())
                        .append("，一级评论数差异 ").append(audit.wrongCounts()).append("。\n");
            }
            stage.issues().forEach(issue -> report.append("- ").append(issue.replace('\n', ' ')).append("\n"));
            String telemetry = "round-" + stage.round() + "/" + stage.scenario() + "-" + stage.load() + "/telemetry.jsonl";
            if (Files.exists(root.resolve(telemetry))) { report.append("- [本档监控时序](").append(telemetry).append(")\n"); }
        }
        Path pending = root.resolve("report.md.tmp");
        Files.writeString(pending, report);
        Files.move(pending, root.resolve("report.md"), StandardCopyOption.REPLACE_EXISTING);
    }

    private static void commentWrite(StringBuilder report, BaselineCommentWrite.Result result) {
        var audit = result.audit();
        var seen = result.observation();
        report.append("- 含预热受理 ").append(audit.accepted()).append(" 笔，落库匹配 ").append(audit.persisted())
                .append(" 笔，额外或错误结果 ").append(audit.unexpectedResults())
                .append("，无对应结果的评论 ").append(audit.untrackedRoots()).append("，文章计数差异 ")
                .append(audit.wrongCounts()).append("，ES 可见 ").append(audit.index().visibleTotal())
                .append(" 条，索引缺失或不符 ").append(audit.index().missingOrWrong()).append("。\n");
        report.append("- 测量期抽样 ").append(seen.samples()).append(" 笔，确认发布 ").append(seen.published())
                .append(" 笔（P95 ").append(format(seen.published() == 0 ? null : seen.publishedP95Ms())).append(" ms）")
                .append("，在最新首页观测到 ").append(seen.visible()).append(" 笔（P95 ")
                .append(format(seen.visible() == 0 ? null : seen.visibleP95Ms())).append(" ms），首页未观测到 ")
                .append(seen.samples() - seen.visible()).append(" 笔。\n");
        report.append("- 索引 Outbox 峰值 ").append(result.outboxPeak()).append("，最老任务 ")
                .append(result.oldestOutboxMs()).append(" ms，死信峰值 ").append(result.deadPeak())
                .append("，排空并完成对账 ").append(format(result.drainMs())).append(" ms。\n");
    }

    private static void row(StringBuilder report, Stage stage, String name, BaselineStats.Route route) {
        String rps = route == null ? "—" : String.format(Locale.ROOT, "%.2f", route.rps());
        report.append("| ").append(stage.round()).append(" / ").append(stage.scenario()).append(" | ")
                .append(stage.load()).append(' ').append(stage.model()).append(" | ").append(name).append(" | ")
                .append(rps).append(" | ").append(route == null ? "—" : route.p95() + " / " + route.p99()).append(" | ")
                .append(route == null ? "—" : route.throttled()).append(" | ").append(stage.backlogPeak()).append(" / ")
                .append(stage.backlogGrowing() ? "增长" : "未判定为持续增长").append(" | ").append(format(stage.rowLockWaits()))
                .append(" / ").append(format(stage.rowLockMs())).append(" | ").append(stage.passed() ? "通过" : "待复核").append(" |\n");
    }

    private static String format(Long value) { return value == null ? "—" : value.toString(); }
}
