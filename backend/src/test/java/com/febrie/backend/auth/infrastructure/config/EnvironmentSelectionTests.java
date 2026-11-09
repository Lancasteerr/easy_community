package com.febrie.backend.auth.infrastructure.config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestContextManager;

import static org.junit.jupiter.api.Assertions.*;

/** 使用隔离工作目录验证真实配置加载；只启用环境校验，不连接数据库、Redis或ES。 */
class EnvironmentSelectionTests {
    @TempDir Path directory;

    @Test
    void defaultsToDevWithoutLoadingUnselectedTemplates() throws Exception {
        write(".env.dev", "SPRING_PROFILES_ACTIVE=prod\nSERVER_PORT=19001\n");
        write(".env.prod", "SPRING_PROFILES_ACTIVE=prod\nSERVER_PORT=19002\n");
        var result = run(directory, Map.of());
        assertEquals("dev", result.getProperty("profiles"));
        assertEquals("dev", result.getProperty("environment"));
        assertEquals("8080", result.getProperty("port"));
        assertEquals("ec:dev", result.getProperty("namespace"));
        assertEquals("false", result.getProperty("bootstrapEnabled"));
    }

    @Test
    void readsCompleteDevFileFromRepositoryAndBackendDirectories() throws Exception {
        write("backend/.env", "SPRING_PROFILES_ACTIVE=dev\nDB_USERNAME=selected_dev\nSERVER_PORT=18090\nADMIN_BOOTSTRAP_ENABLED=true\n");
        for (Path workingDirectory : List.of(directory, directory.resolve("backend"))) {
            var result = run(workingDirectory, Map.of());
            assertEquals("dev", result.getProperty("profiles"));
            assertEquals("selected_dev", result.getProperty("databaseUser"));
            assertEquals("18090", result.getProperty("port"));
            assertEquals("true", result.getProperty("bootstrapEnabled"));
        }
    }

    @Test
    void backendFileWinsWhenBothLocationsExist() throws Exception {
        write(".env", "SPRING_PROFILES_ACTIVE=dev\nSERVER_PORT=18090\n");
        write("backend/.env", "SPRING_PROFILES_ACTIVE=prod\nSERVER_PORT=18091\nTRUSTED_PROXY_REGEX=172[.]31[.]0[.]2\n");
        var result = run(directory, Map.of());
        assertEquals("prod", result.getProperty("profiles"));
        assertEquals("18091", result.getProperty("port"));
        assertEquals("true", result.getProperty("secureCookie"));
        assertEquals("native", result.getProperty("forwardHeaders"));
        assertEquals("ec:prod", result.getProperty("namespace"));
    }

    @Test
    void explicitProdFileUsesOnlySelectedFile() throws Exception {
        write(".env", "SPRING_PROFILES_ACTIVE=prod\nDB_USERNAME=selected_prod\nTRUSTED_PROXY_REGEX=172[.]31[.]0[.]2\n");
        write(".env.dev", "DB_USERNAME=unselected_dev\n");
        write(".env.prod", "DB_USERNAME=unselected_prod\n");
        var result = run(directory, Map.of());
        assertEquals("prod", result.getProperty("environment"));
        assertEquals("selected_prod", result.getProperty("databaseUser"));
    }

    @Test
    void systemEnvironmentAndCommandLineOverrideFileSelection() throws Exception {
        write(".env", "SPRING_PROFILES_ACTIVE=dev\nSERVER_PORT=18090\nTRUSTED_PROXY_REGEX=172[.]31[.]0[.]2\n");
        var variables = Map.of("SPRING_PROFILES_ACTIVE", "prod", "SERVER_PORT", "18091",
                "ADMIN_BOOTSTRAP_ENABLED", "true");
        var system = run(directory, variables);
        assertEquals("prod", system.getProperty("profiles"));
        assertEquals("18091", system.getProperty("port"));
        assertEquals("true", system.getProperty("bootstrapEnabled"));
        var command = run(directory, variables, "--spring.profiles.active=test", "--server.port=18092",
                "--app.bootstrap.enabled=false");
        assertEquals("test", command.getProperty("profiles"));
        assertEquals("test", command.getProperty("environment"));
        assertEquals("18092", command.getProperty("port"));
        assertEquals("false", command.getProperty("bootstrapEnabled"));
    }

    @Test
    void activeProfilesAnnotationOverridesLocalDevSelection() throws Exception {
        write(".env", "SPRING_PROFILES_ACTIVE=dev\nADMIN_BOOTSTRAP_ENABLED=true\n");
        var result = run(directory, Map.of(), "--probe.test-context=true");
        assertEquals("test", result.getProperty("profiles"));
        assertEquals("test", result.getProperty("environment"));
        assertEquals("ec:test", result.getProperty("namespace"));
        assertEquals("false", result.getProperty("bootstrapEnabled"));
    }

    @Test
    void rejectsMixedProfilesAndIncompleteProductionConfiguration() throws Exception {
        write(".env", "SPRING_PROFILES_ACTIVE=dev,prod\n");
        assertNotEquals(0, launch(directory, Map.of()).exitCode());
        write(".env", "SPRING_PROFILES_ACTIVE=prod\n");
        assertNotEquals(0, launch(directory, Map.of()).exitCode());
    }

    private void write(String filename, String content) throws Exception {
        Path file = directory.resolve(filename);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private Properties run(Path workingDirectory, Map<String, String> variables, String... arguments) throws Exception {
        var process = launch(workingDirectory, variables, arguments);
        assertEquals(0, process.exitCode(), () -> "环境配置加载失败，检查日志：" + process.log());
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(process.snapshot(), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private ProbeResult launch(Path workingDirectory, Map<String, String> variables, String... arguments) throws Exception {
        Path output = Files.createTempDirectory(directory, "probe-");
        Path snapshot = output.resolve("environment.properties");
        Path log = output.resolve("startup.log");
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString()));
        String temporary = System.getProperty("jdk.net.unixdomain.tmpdir");
        if (temporary != null) { command.add("-Djdk.net.unixdomain.tmpdir=" + temporary); }
        command.addAll(List.of("-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                Probe.class.getName(), "--probe.output=" + snapshot));
        command.addAll(List.of(arguments));
        var builder = new ProcessBuilder(command).directory(workingDirectory.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile());
        // 移除宿主机运行参数，避免本机凭证或环境选择污染隔离测试。
        builder.environment().keySet().removeIf(key -> key.startsWith("SPRING_") || key.startsWith("APP_")
                || key.equals("SERVER_PORT") || key.equals("DB_USERNAME") || key.equals("TRUSTED_PROXY_REGEX")
                || key.equals("ADMIN_BOOTSTRAP_ENABLED"));
        builder.environment().putAll(variables);
        var process = builder.start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "配置探针未退出：" + log);
            return new ProbeResult(process.exitValue(), snapshot, log);
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
        }
    }

    private record ProbeResult(int exitCode, Path snapshot, Path log) { }

    /** 使用实际配置资源启动最小上下文，保留生产安全检查且不扫描业务组件。 */
    @Configuration(proxyBeanMethods = false)
    @Import(RuntimeConfiguration.class)
    static class ProbeConfiguration { }

    /** 通过真实测试上下文加载器覆盖本机环境，验证现有集成测试的环境隔离。 */
    @SpringBootTest(classes = ProbeConfiguration.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
    @ActiveProfiles("test")
    static class AnnotatedTest { }

    /** 子进程只导出非敏感的配置结果，不输出数据库密码或其他密钥。 */
    public static class Probe {
        public static void main(String[] args) throws Exception {
            var arguments = new org.springframework.boot.DefaultApplicationArguments(args);
            ConfigurableApplicationContext context;
            if (arguments.containsOption("probe.test-context")) {
                context = (ConfigurableApplicationContext) new TestContextManager(AnnotatedTest.class)
                        .getTestContext().getApplicationContext();
            } else {
                var application = new SpringApplication(ProbeConfiguration.class);
                application.setWebApplicationType(WebApplicationType.NONE);
                application.setLogStartupInfo(false);
                context = application.run(args);
            }
            try (context) {
                var env = context.getEnvironment();
                var result = new Properties();
                result.setProperty("profiles", String.join(",", env.getActiveProfiles()));
                result.setProperty("environment", env.getRequiredProperty("app.auth.environment"));
                result.setProperty("namespace", env.getRequiredProperty("app.auth.redis-prefix"));
                result.setProperty("secureCookie", env.getRequiredProperty("app.auth.secure-cookie"));
                result.setProperty("forwardHeaders", env.getRequiredProperty("server.forward-headers-strategy"));
                result.setProperty("port", env.getProperty("server.port", "8080"));
                result.setProperty("databaseUser", env.getProperty("DB_USERNAME", ""));
                result.setProperty("bootstrapEnabled", env.getProperty("app.bootstrap.enabled", "false"));
                try (var writer = Files.newBufferedWriter(Path.of(arguments.getOptionValues("probe.output").getFirst()), StandardCharsets.UTF_8)) {
                    result.store(writer, "Isolated environment selection result");
                }
            }
        }
    }
}
