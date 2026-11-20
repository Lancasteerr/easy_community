package com.febrie.backend.performance;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 每档单独执行 k6，预热与测量各自保存原始样本，避免统计混窗。 */
final class BaselineK6 {
    record Stage(String scenario, int load, int vus) {
        String directory() { return scenario + "-" + load; }
        boolean closed() { return scenario.equals("mixed") || scenario.equals("like-probe"); }
        boolean writes() { return scenario.startsWith("like") || scenario.equals("mixed"); }
        boolean readsDetails() { return scenario.startsWith("detail") || scenario.equals("mixed"); }
        int userLimit() { return scenario.startsWith("like") || scenario.startsWith("detail") ? 100000 : 120; }
        int likeLimit() { return scenario.startsWith("like") ? 100000 : 60; }
    }

    private BaselineK6() { }

    static List<Stage> stages(String mode, String selected) {
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
        return List.copyOf(stages);
    }

    private static void add(List<Stage> stages, String selected, String group, String scenario, int[] rates, int vus) {
        if (selected.equals("All") || selected.equals(group)) {
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
