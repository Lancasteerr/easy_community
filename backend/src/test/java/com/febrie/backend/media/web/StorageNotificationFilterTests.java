package com.febrie.backend.media.web;

import com.febrie.backend.media.application.*;
import com.febrie.backend.media.application.dto.UploadResult;
import com.febrie.backend.media.infrastructure.config.*;
import com.febrie.backend.media.infrastructure.storage.PostPolicies;
import jakarta.servlet.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;
import static com.febrie.backend.media.application.dto.UploadResult.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 从真实Filter检查入口起点、同步读取和生产504空正文，不依赖定时器或等待五秒。 */
class StorageNotificationFilterTests {
    private static final String PATH = "/api/storage/cos/upload-callback";
    private static final String KEY = "callback-test-key";
    private static final String ID = "00112233-4455-6677-8899-aabbccddeeff";
    private static final byte[] BODY = ("{\"bucket\":\"media-test\",\"key\":\"ec/test/temp/" + ID
            + "\",\"size\":123,\"mimeType\":\"image/png\",\"etag\":\"test-etag\"}").getBytes(StandardCharsets.UTF_8);
    private final AtomicLong nanos = new AtomicLong();
    private final FinishUpload finish = mock(FinishUpload.class);
    private final MediaWorkflowProperties flow = new Binder(new MapConfigurationPropertySource(Map.of(
            "app.media.workflow.callback-key", KEY)))
            .bindOrCreate("app.media.workflow", Bindable.of(MediaWorkflowProperties.class));

    private StorageNotificationHandler handler() {
        var p = new MediaProperties("cos", URI.create("https://storage.example.com"), URI.create("https://storage.example.com"),
                "ap-guangzhou", "media-test", "test-access", "test-secret", "ec/test", URI.create("https://images.example.com"));
        var env = new MockEnvironment(); env.setActiveProfiles("prod");
        return new StorageNotificationHandler(p, flow, finish, env);
    }
    private StorageNotificationFilter filter() { return new StorageNotificationFilter(handler(), flow, nanos::get); }
    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("POST", PATH);
        request.setServletPath(PATH); request.setContentType("application/json"); request.setContent(BODY);
        byte[] prefix = (PATH + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] input = new byte[prefix.length + BODY.length];
        System.arraycopy(prefix, 0, input, 0, prefix.length); System.arraycopy(BODY, 0, input, prefix.length, BODY.length);
        request.addHeader("x-cos-callback-encrypted-auth", Base64.getEncoder().encodeToString(
                PostPolicies.hmac("HmacSHA1", KEY.getBytes(StandardCharsets.UTF_8), PostPolicies.digest("SHA-1", input))));
        return request;
    }
    private void execute(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        filter().doFilter(request, response, (input, output) -> fail("通知不能进入普通业务链"));
        assertFalse(request.isAsyncStarted());
    }
    private static void assertEmptyTimeout(MockHttpServletResponse response) {
        assertEquals(504, response.getStatus());
        assertEquals(0, response.getContentAsByteArray().length);
        assertEquals("0", response.getHeader("Content-Length"));
        assertNull(response.getContentType());
        assertNull(response.getHeader("X-Media-Code"));
    }
    @Test void bodyReadConsumesTheEntryBudgetAndNeverInvokesPublish() throws Exception {
        var request = spy(request());
        doReturn(new TimedBody(BODY, () -> nanos.set(4_500_000_000L))).when(request).getInputStream();
        var response = new MockHttpServletResponse();
        execute(request, response);
        assertEmptyTimeout(response);
        verifyNoInteractions(finish);
    }
    @Test void headerChecksDoNotResetTheStartTime() throws Exception {
        var handler = spy(handler());
        doAnswer(call -> { nanos.set(4_500_000_000L); return true; }).when(handler).acceptsAuthenticationHeader(any());
        var request = spy(request());
        var response = new MockHttpServletResponse();
        new StorageNotificationFilter(handler, flow, nanos::get).doFilter(request, response, (input, output) -> fail());
        assertEmptyTimeout(response);
        verify(request, never()).getInputStream();
    }
    @Test void bodyAndSignatureVerificationUseOneSharedBudget() throws Exception {
        var request = spy(request());
        doReturn(new TimedBody(BODY, () -> nanos.addAndGet(2_500_000_000L))).when(request).getInputStream();
        String signature = request.getHeader("x-cos-callback-encrypted-auth");
        doAnswer(call -> { nanos.addAndGet(2_100_000_000L); return signature; })
                .when(request).getHeader("x-cos-callback-encrypted-auth");
        var response = new MockHttpServletResponse();
        execute(request, response);
        assertEmptyTimeout(response);
        verifyNoInteractions(finish);
    }
    @ParameterizedTest @EnumSource(value = Stage.class, names = {"AUTH", "MODERATION", "COPY", "COMMIT"})
    void internalTimeoutAtEveryStageProducesAnEmpty504(Stage stage) throws Exception {
        when(finish.finishUpload(any(), any())).thenReturn(failed(stage, Code.PROCESS_TIMEOUT));
        var response = new MockHttpServletResponse();
        execute(request(), response);
        assertEmptyTimeout(response);
        verify(finish).finishUpload(any(), any());
    }
    @Test void normalSuccessAndBusinessFailureKeepTheirJsonContract() throws Exception {
        for (UploadResult result : List.of(completed(), failed(Stage.MODERATION, Code.MODERATION_REJECTED), failed(Stage.COPY, Code.COPY_FAILED))) {
            when(finish.finishUpload(any(), any())).thenReturn(result);
            var response = new MockHttpServletResponse();
            execute(request(), response);
            assertEquals(200, response.getStatus());
            assertEquals(result.code().name(), response.getHeader("X-Media-Code"));
            assertEquals(ID, response.getHeader("X-Media-Id"));
            assertTrue(response.getContentAsString().contains("\"success\":" + result.success()));
        }
    }
    @Test void bodyLimitAndReadTimeoutRemainTransportFailures() throws Exception {
        var oversized = request(); oversized.setContent(new byte[flow.callbackMaxBytes() + 1]);
        var tooLarge = new MockHttpServletResponse();
        execute(oversized, tooLarge);
        assertEquals(413, tooLarge.getStatus());
        var broken = spy(request());
        doThrow(new java.net.SocketTimeoutException("test read timeout")).when(broken).getInputStream();
        var timeout = new MockHttpServletResponse();
        execute(broken, timeout);
        assertEmptyTimeout(timeout);
        verifyNoInteractions(finish);
    }
    /** 只有一次非空分段读取推进时间，禁止使用异步ReadListener。 */
    private static final class TimedBody extends ServletInputStream {
        private final ByteArrayInputStream bytes;
        private final Runnable read;
        TimedBody(byte[] body, Runnable read) { bytes = new ByteArrayInputStream(body); this.read = read; }
        @Override public int read() { int value = bytes.read(); if (value >= 0) { read.run(); } return value; }
        @Override public int read(byte[] buffer, int offset, int length) {
            int count = bytes.read(buffer, offset, length); if (count > 0) { read.run(); } return count;
        }
        @Override public boolean isFinished() { return bytes.available() == 0; }
        @Override public boolean isReady() { return true; }
        @Override public void setReadListener(ReadListener listener) { throw new AssertionError("必须同步读取"); }
    }
}
