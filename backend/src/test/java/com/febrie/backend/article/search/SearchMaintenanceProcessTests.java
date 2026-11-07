package com.febrie.backend.article.search;

import com.febrie.backend.article.search.infrastructure.config.SearchConfiguration;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** 通过真实main验证维护Runner与自动退出，不只测试底层服务方法。 */
@EnabledIfEnvironmentVariable(named = "IT_SEARCH", matches = "true")
class SearchMaintenanceProcessTests {
    @Test void nonWebCommandsInitializeImportCalibrateAuditAndExit() throws Exception {
        String target = "article_cli_" + UUID.randomUUID().toString().replace("-", "");
        String url = System.getenv("IT_DB_URL");
        if (url == null || !url.contains("/easy_community_search_test?")) { throw new IllegalArgumentException("进程测试只允许检索专用数据库"); }
        try (var es = new SearchConfiguration().articleElasticsearch(SearchTestSupport.properties(Map.of()))) {
            try {
                for (String action : List.of("init", "init", "import", "calibrate", "audit")) {
                    List<String> args = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString()));
                    String temporary = System.getProperty("jdk.net.unixdomain.tmpdir");
                    if (temporary != null) { args.add("-Djdk.net.unixdomain.tmpdir=" + temporary); }
                    args.addAll(List.of("-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                            "com.febrie.backend.BackendApplication", "--spring.profiles.active=test", "--spring.main.web-application-type=none",
                            "--app.auth.redis-prefix=ec:test:searchcli:" + target, "--app.search.maintenance=" + action, "--app.search.target-index=" + target));
                    Path log = Path.of("target", "search-cli-" + action + ".log");
                    var process = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile()).start();
                    try {
                        assertTrue(process.waitFor(45, TimeUnit.SECONDS), "维护命令未自动退出：" + log);
                        assertEquals(0, process.exitValue(), "检查维护命令日志：" + log);
                        assertTrue(Files.readString(log).contains("\"action\":\"" + action + "\""));
                    } finally { if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); } }
                }
            } finally { es.indices().delete(d -> d.index(target).ignoreUnavailable(true)); }
        }
    }
}
