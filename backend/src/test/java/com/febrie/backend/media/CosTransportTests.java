package com.febrie.backend.media;

import com.febrie.backend.media.application.*;
import com.febrie.backend.media.infrastructure.config.MediaProperties;
import com.febrie.backend.media.infrastructure.storage.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 通过真实SDK读取本地HTTP响应，验证200错误体、未知审核结论及关闭重试。 */
class CosTransportTests {
    @Test void realSdkReadTimeoutIsClassifiedWithoutRetry() throws Exception {
        try (var server = new ResponseServer(200, "<RecognitionResult><State>Success</State><Result>0</Result></RecognitionResult>", 1000)) {
            var moderation = new CosContentModeration(server.properties(), PostPolicyTests.workflow());
            assertThrows(ProcessingDeadline.TimedOut.class, () -> moderation.review(object(), new ProcessingDeadline(Duration.ofMillis(500))));
            assertEquals(1, server.requests.get());
        }
        try (var server = new ResponseServer(200, "<CopyObjectResult><ETag>test-etag</ETag></CopyObjectResult>", 1000)) {
            var storage = new CosUploadStorage(server.properties(), PostPolicyTests.workflow());
            assertThrows(ProcessingDeadline.TimedOut.class, () -> storage.copy(object(), "ec/test/public/id", new ProcessingDeadline(Duration.ofMillis(500))));
            assertEquals(1, server.requests.get());
        }
    }
    @Test void copyRejectsErrorInsideHttp200WithoutRetry() throws Exception {
        try (var server = new ResponseServer(200, "<Error><Code>InternalError</Code><Message>test</Message></Error>")) {
            var storage = new CosUploadStorage(server.properties(), PostPolicyTests.workflow());
            assertThrows(RuntimeException.class, () -> storage.copy(object(), "ec/test/public/id", deadline()));
            assertEquals(1, server.requests.get());
        }
    }
    @Test void moderationOnlyAcceptsExplicitPassAndDoesNotRetry503() throws Exception {
        for (String result : new String[]{"0", "1", "2", "unknown"}) {
            try (var server = new ResponseServer(200, "<RecognitionResult><State>Success</State><Result>" + result + "</Result></RecognitionResult>")) {
                var moderation = new CosContentModeration(server.properties(), PostPolicyTests.workflow());
                if (result.equals("unknown")) { assertThrows(RuntimeException.class, () -> moderation.review(object(), deadline())); }
                else { assertEquals(switch (result) { case "0" -> ContentModeration.Decision.PASS; case "1" -> ContentModeration.Decision.REJECT; default -> ContentModeration.Decision.REVIEW_REQUIRED; }, moderation.review(object(), deadline())); }
                assertEquals(1, server.requests.get());
            }
        }
        try (var server = new ResponseServer(503, "<Error><Code>InternalError</Code></Error>")) {
            assertThrows(RuntimeException.class, () -> new CosContentModeration(server.properties(), PostPolicyTests.workflow()).review(object(), deadline()));
            assertEquals(1, server.requests.get());
        }
    }
    private static UploadedObject object() { return new UploadedObject("media-test", "ec/test/temp/id", 123, "image/png", "etag"); }
    private static ProcessingDeadline deadline() { return new ProcessingDeadline(Duration.ofMillis(4500)); }
    /** 阻塞ServerSocket不依赖Windows JDK的临时Unix域套接字路径。 */
    private static final class ResponseServer implements AutoCloseable {
        private final ServerSocket socket = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        final AtomicInteger requests = new AtomicInteger();
        ResponseServer(int status, String body) throws IOException {
            this(status, body, 0);
        }
        ResponseServer(int status, String body, long delayMillis) throws IOException {
            executor.submit(() -> {
                try {
                    while (!socket.isClosed()) {
                        try (Socket connection = socket.accept()) {
                            connection.setSoTimeout(2000);
                            var reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII));
                            String line;
                            while ((line = reader.readLine()) != null && !line.isEmpty()) { /* 只读头，不记录签名。 */ }
                            requests.incrementAndGet(); byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                            if (delayMillis > 0) { Thread.sleep(delayMillis); }
                            connection.getOutputStream().write(("HTTP/1.1 " + status + " Test\r\nContent-Type: application/xml\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                            connection.getOutputStream().write(bytes);
                        }
                    }
                } catch (IOException ignored) { /* 测试结束时关闭accept。 */ }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
        }
        MediaProperties properties() {
            URI endpoint = URI.create("http://127.0.0.1:" + socket.getLocalPort());
            return new MediaProperties("cos", endpoint, endpoint, "ap-guangzhou", "media-test", "test-key", "test-secret", "ec/test", endpoint);
        }
        @Override public void close() throws IOException { socket.close(); executor.shutdownNow(); }
    }
}
