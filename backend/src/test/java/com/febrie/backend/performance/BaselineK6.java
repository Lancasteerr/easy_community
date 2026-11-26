package com.febrie.backend.performance;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 每档单独执行 k6，预热与测量各自保存原始样本，避免统计混窗。 */
final class BaselineK6 {
    enum Model {
        VU("VU"), RPS("RPS"), JOURNEYS("旅程/秒");

        final String label;
        Model(String label) { this.label = label; }
    }

    record Stage(String scenario, int load, int vus, Model model) {
        Stage(String scenario, int load, int vus) {
            this(scenario, load, vus, scenario.equals("mixed") || scenario.equals("like-probe") ? Model.VU : Model.RPS);
        }
        String directory() { return scenario + "-" + load; }
        boolean closed() { return model == Model.VU; }
        boolean mixed() { return scenario.equals("mixed"); }
        boolean needsSessions() { return !scenario.equals("search"); }
        boolean readsComments() { return scenario.equals("comment-read"); }
        boolean writesComments() { return scenario.startsWith("comment-write-"); }
        boolean writesReplies() { return scenario.startsWith("comment-reply-write-"); }
        boolean comments() { return readsComments() || writesComments() || writesReplies(); }
        boolean writes() { return scenario.startsWith("like") || scenario.equals("mixed"); }
        boolean readsDetails() { return scenario.startsWith("detail") || scenario.equals("mixed"); }
        int userLimit() { return scenario.startsWith("like") || scenario.startsWith("detail") || comments() ? 100000 : 120; }
        int likeLimit() { return scenario.startsWith("like") ? 100000 : 60; }
    }

    private BaselineK6() { }

    static List<Stage> stages(String mode, String selected) {
        return stages(mode, selected, List.of());
    }

    static int rounds(String mode, String value) {
        if (value == null || value.isBlank()) { return mode.equals("Smoke") ? 1 : 3; }
        int rounds = Integer.parseInt(value);
        if (rounds < 1 || rounds > 5) { throw new IllegalArgumentException("Rounds 仅支持 1–5"); }
        return rounds;
    }

    static List<Integer> rates(String value) {
        if (value == null || value.isEmpty()) { return List.of(); }
        var rates = new LinkedHashSet<Integer>();
        for (String part : value.split(",", -1)) {
            if (!part.matches("[0-9]+")) { throw new IllegalArgumentException("Rps 需要逗号分隔的整数"); }
            int rate = Integer.parseInt(part);
            if (rate < 1 || rate > 1000 || !rates.add(rate)) {
                throw new IllegalArgumentException("Rps 需为 1–1000 的不重复整数");
            }
        }
        return List.copyOf(rates);
    }

    static List<Stage> stages(String mode, String selected, List<Integer> rates) {
        if (selected == null) { selected = "All"; }
        boolean smoke = mode.equals("Smoke");
        var stages = new ArrayList<Stage>();
        if (!mode.equals("Exploration")) {
            add(stages, selected, "Search", "search", smoke ? new int[]{15} : new int[]{50, 100, 200}, smoke ? 8 : 64);
            add(stages, selected, "Detail", "detail-cold", new int[]{smoke ? 15 : 50}, smoke ? 8 : 64);
            add(stages, selected, "Detail", "detail", smoke ? new int[]{15} : new int[]{50, 100, 200}, smoke ? 8 : 64);
            add(stages, selected, "Mixed", "mixed", smoke ? new int[]{10} : new int[]{50, 100, 200}, 0);
        }
        int[] writes = smoke ? new int[]{25} : mode.equals("Exploration") ? new int[]{240} : new int[]{60, 120};
        add(stages, selected, "Like", "like-spread", writes, smoke ? 8 : 96);
        add(stages, selected, "Like", "like-hot", writes, smoke ? 8 : 96);
        if (smoke) { add(stages, selected, "Like", "like-probe", new int[]{8}, 0); }
        boolean exploration = mode.equals("Exploration");
        if (!exploration && (selected.equalsIgnoreCase("Comment") || selected.equalsIgnoreCase("CommentRead")
                || selected.equalsIgnoreCase("All"))) {
            add(stages, "CommentRead", "CommentRead", "comment-read", smoke ? new int[]{5} : new int[]{25, 50, 100},
                    smoke ? 8 : 64);
        }
        if (selected.equalsIgnoreCase("CommentWrite")
                || !exploration && (selected.equalsIgnoreCase("Comment") || selected.equalsIgnoreCase("All"))) {
            int[] commentRates = smoke ? new int[]{2} : exploration ? new int[]{20} : new int[]{5, 10, 15};
            add(stages, "CommentWrite", "CommentWrite", "comment-write-spread", commentRates, smoke ? 8 : 64);
            add(stages, "CommentWrite", "CommentWrite", "comment-write-hot", commentRates, smoke ? 8 : 64);
        }
        // 回复写档独立选择，避免改变现有 All 和 Comment 的基线耗时及历史口径。
        if (!exploration && selected.equalsIgnoreCase("CommentReplyWrite")) {
            int[] replyRates = smoke ? new int[]{2} : new int[]{10, 25, 50};
            add(stages, selected, "CommentReplyWrite", "comment-reply-write-spread", replyRates, smoke ? 8 : 64);
            add(stages, selected, "CommentReplyWrite", "comment-reply-write-hot", replyRates, smoke ? 8 : 64);
        }
        if (rates.isEmpty()) { return List.copyOf(stages); }
        // 同一场景只展开一次自定义阶梯，保留用户输入顺序。
        return stages.stream().map(Stage::scenario).distinct()
                .flatMap(scenario -> rates.stream().map(rate -> custom(scenario, rate, smoke)))
                .toList();
    }

    private static Stage custom(String scenario, int rate, boolean smoke) {
        if (scenario.equals("mixed")) { return new Stage(scenario, rate, rate * 4, Model.JOURNEYS); }
        // 单请求场景为半秒在途预留槽位，恢复探针还包含迭代内等待。
        int floor = smoke ? 8 : scenario.startsWith("like") ? 96 : 64;
        int vus = scenario.equals("like-probe") ? rate : Math.ceilDiv(rate, 2);
        return new Stage(scenario, rate, Math.max(floor, vus), Model.RPS);
    }

    private static void add(List<Stage> stages, String selected, String group, String scenario, int[] rates, int vus) {
        if (selected.equalsIgnoreCase("All") || selected.equalsIgnoreCase(group)) {
            for (int load : rates) { stages.add(new Stage(scenario, load, vus == 0 ? load : vus)); }
        }
    }

    static Process start(Path script, Path input, Path output, String phase, Stage stage, int seconds) throws IOException {
        var command = new ProcessBuilder("k6", "run", "--no-color", "--quiet", "--log-output", "stdout",
                "--log-format", "raw", "--new-machine-readable-summary", "--summary-trend-stats", "avg,min,med,max,p(95),p(99)",
                "--summary-export", output.resolve(phase + "-summary.json").toString(),
                "--out", "json=" + output.resolve(phase + "-metrics.json.gz"), script.toString());
        // k6 只需载荷参数，避免把服务密码和外部 k6 输出配置传给脚本。
        command.environment().keySet().removeIf(key -> key.startsWith("K6_") || key.contains("PASSWORD"));
        command.environment().put("EC_BASELINE_INPUT", input.toString());
        command.environment().put("EC_BASELINE_SCENARIO", stage.scenario());
        command.environment().put("EC_BASELINE_DURATION", Integer.toString(seconds));
        command.environment().put("EC_BASELINE_LOAD", Integer.toString(stage.load()));
        command.environment().put("EC_BASELINE_VUS", Integer.toString(stage.vus()));
        command.environment().put("EC_BASELINE_MODEL", stage.model().name());
        command.environment().put("EC_BASELINE_OP", UUID.randomUUID().toString().substring(0, 8));
        return command.redirectOutput(output.resolve(phase + "-http.log").toFile())
                .redirectError(output.resolve(phase + "-k6.log").toFile()).start();
    }

    static void verifyExit(Process process, int seconds) throws Exception {
        try {
            if (!process.waitFor(seconds + 25L, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IllegalStateException("k6 执行失败或超时，详见本档 k6 日志");
            }
        } finally {
            if (process.isAlive()) { process.destroyForcibly().waitFor(); }
        }
    }
}
