package com.febrie.backend.media;

import com.febrie.backend.media.application.*;
import com.febrie.backend.media.application.dto.UploadResult;
import com.febrie.backend.media.web.StorageNotificationHandler;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import com.febrie.backend.media.infrastructure.config.MediaWorkflowProperties;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 多条事件全部适配，非POST事件不能发布，认证与JSON解析相互独立。 */
class MinioWebhookTests {
    @Test void verifiesBearerAndProcessesEveryRecordWithoutCopyEvents() {
        var flow = new Binder(new MapConfigurationPropertySource(Map.of("app.media.workflow.webhook-token", "test-token")))
                .bindOrCreate("app.media.workflow", Bindable.of(MediaWorkflowProperties.class));
        var finish = mock(FinishUpload.class);
        when(finish.finishUpload(any(), any())).thenReturn(UploadResult.completed());
        var env = new MockEnvironment(); env.setActiveProfiles("dev");
        var handler = new StorageNotificationHandler(StorageSignatureTests.properties("minio"), flow, finish, env);
        var request = new MockHttpServletRequest();
        assertFalse(handler.acceptsAuthenticationHeader(request));
        request.addHeader("Authorization", "Bearer test-token");
        assertTrue(handler.acceptsAuthenticationHeader(request));
        String record = """
                {"eventName":"s3:ObjectCreated:Post","s3":{"bucket":{"name":"media-test"},"object":{
                  "key":"ec%2Ftest%2Ftemp%2Fid","size":123,"eTag":"etag","userMetadata":{"content-type":"image/png"}}}}
                """;
        byte[] body = ("{\"Records\":[" + record + "," + record + "," + record.replace("Created:Post", "Created:Copy") + "]}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(200, handler.handle(request, body, new ProcessingDeadline(Duration.ofMillis(4500))).status());
        verify(finish, times(2)).finishUpload(argThat(object -> object.key().equals("ec/test/temp/id") && object.size() == 123), any());
        assertFalse(handler.accepts("/api/storage/cos/upload-callback"));
    }
}
