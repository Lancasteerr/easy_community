package com.febrie.backend.media.web;

import com.febrie.backend.media.application.ProcessingDeadline;
import com.febrie.backend.media.infrastructure.config.MediaWorkflowProperties;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.net.SocketTimeoutException;
import java.util.function.LongSupplier;
import org.slf4j.*;
import static com.febrie.backend.media.application.dto.UploadResult.*;

/** 单条同步通知链：入口起计时，分段读取与阶段调用返回后检查，超时直接结束。 */
public final class StorageNotificationFilter implements Filter {
    private static final Logger log = LoggerFactory.getLogger(StorageNotificationFilter.class);
    private final StorageNotificationHandler handler;
    private final MediaWorkflowProperties flow;
    private final LongSupplier ticker;
    public StorageNotificationFilter(StorageNotificationHandler handler, MediaWorkflowProperties flow) {
        this(handler, flow, System::nanoTime);
    }
    /** 测试可推进单调时钟，验证读取及验签使用入口的同一起点。 */
    StorageNotificationFilter(StorageNotificationHandler handler, MediaWorkflowProperties flow, LongSupplier ticker) {
        this.handler = handler; this.flow = flow; this.ticker = ticker;
    }
    @Override public void doFilter(ServletRequest input, ServletResponse output, FilterChain chain) throws IOException {
        ProcessingDeadline deadline = new ProcessingDeadline(flow.processingBudget(), ticker);
        var request = (HttpServletRequest) input;
        var response = (HttpServletResponse) output;
        try {
            if (!handler.accepts(request.getServletPath())) { NotificationResponses.write(response, 404, null, failed(Stage.AUTH, Code.INVALID_CALLBACK)); return; }
            if (!"POST".equals(request.getMethod())) { NotificationResponses.write(response, 405, null, failed(Stage.AUTH, Code.INVALID_CALLBACK)); return; }
            boolean authenticated = handler.acceptsAuthenticationHeader(request);
            deadline.check();
            if (!authenticated) { NotificationResponses.write(response, 403, null, failed(Stage.AUTH, Code.INVALID_CALLBACK)); return; }
            if (request.getContentType() == null || !request.getContentType().split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
                NotificationResponses.write(response, 415, null, failed(Stage.AUTH, Code.INVALID_CALLBACK)); return;
            }
            if (request.getContentLengthLong() > handler.maxBytes()) {
                NotificationResponses.write(response, 413, null, failed(Stage.AUTH, Code.INVALID_CALLBACK)); return;
            }
            var body = new ByteArrayOutputStream();
            var stream = request.getInputStream();
            byte[] buffer = new byte[4096];
            while (true) {
                deadline.check();
                // 阻塞读取依靠容器/代理读超时；应用不另起线程抢占或关闭响应。
                int count = stream.read(buffer);
                deadline.check();
                if (count < 0) { break; }
                if (body.size() + count > handler.maxBytes()) { NotificationResponses.write(response, 413, null, failed(Stage.AUTH, Code.INVALID_CALLBACK)); return; }
                body.write(buffer, 0, count);
            }
            var result = handler.handle(request, body.toByteArray(), deadline);
            // 已验证的开发Webhook可在发布超时后确认接收，不能在此改写其确认结果。
            NotificationResponses.write(response, result.status(), result.mediaId(), result.result());
        } catch (ProcessingDeadline.TimedOut timeout) {
            log.info("通知入口结束：stage=AUTH code=PROCESS_TIMEOUT elapsedMs={}", deadline.elapsedMillis());
            NotificationResponses.write(response, 504, null, null);
        } catch (IOException failure) {
            if (response.isCommitted()) { throw failure; }
            if (deadline.expired() || failure instanceof SocketTimeoutException) {
                log.info("通知入口结束：stage=AUTH code=PROCESS_TIMEOUT elapsedMs={}", deadline.elapsedMillis());
                NotificationResponses.write(response, 504, null, null);
            } else {
                NotificationResponses.write(response, 400, null, failed(Stage.AUTH, Code.INVALID_CALLBACK));
            }
        }
    }
}
