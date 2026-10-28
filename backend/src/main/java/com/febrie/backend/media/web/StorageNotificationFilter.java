package com.febrie.backend.media.web;

import com.febrie.backend.media.application.ProcessingDeadline;
import com.febrie.backend.media.infrastructure.config.MediaWorkflowProperties;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static com.febrie.backend.media.application.dto.UploadResult.*;

/** 非阻塞限量读取请求体，Servlet异步超时覆盖慢请求；不启动后台发布任务。 */
public final class StorageNotificationFilter implements Filter {
    private final StorageNotificationHandler handler;
    private final MediaWorkflowProperties flow;
    private final ScheduledExecutorService timers;
    public StorageNotificationFilter(StorageNotificationHandler handler, MediaWorkflowProperties flow, ScheduledExecutorService timers) {
        this.handler = handler; this.flow = flow; this.timers = timers;
    }
    @Override public void doFilter(ServletRequest input, ServletResponse output, FilterChain chain) throws IOException {
        var request = (HttpServletRequest) input;
        var response = (HttpServletResponse) output;
        if (!handler.accepts(request.getServletPath())) { NotificationResponses.write(response, 404, null, failed(Stage.AUTH, Code.INVALID_CALLBACK)); return; }
        if (!"POST".equals(request.getMethod())) { NotificationResponses.write(response, 405, null, failed(Stage.AUTH, Code.INVALID_CALLBACK)); return; }
        if (!handler.acceptsAuthenticationHeader(request)) { NotificationResponses.write(response, 403, null, failed(Stage.AUTH, Code.INVALID_CALLBACK)); return; }
        ProcessingDeadline deadline = new ProcessingDeadline(flow.processingBudget());
        if (request.getContentType() == null || !request.getContentType().split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
            NotificationResponses.write(response, 415, null, failed(Stage.AUTH, Code.INVALID_CALLBACK)); return;
        }
        if (request.getContentLengthLong() > handler.maxBytes()) {
            NotificationResponses.write(response, 413, null, failed(Stage.AUTH, Code.INVALID_CALLBACK)); return;
        }
        AsyncContext context = request.startAsync();
        // 容器异步超时可能按秒扫描，另用精确定时器控制4.5秒截止，容器仅作为兜底。
        context.setTimeout(5000);
        AtomicBoolean responded = new AtomicBoolean();
        AtomicReference<ScheduledFuture<?>> timer = new AtomicReference<>();
        // 超时可能与正在结束的SDK调用并发；只有一个分支能写响应，迟到流程仍须检查同一deadline。
        class Completion {
            void complete(StorageNotificationHandler.Response result) {
                if (!responded.compareAndSet(false, true)) { return; }
                ScheduledFuture<?> pending = timer.get();
                if (pending != null) { pending.cancel(false); }
                try { NotificationResponses.write(response, result.status(), result.mediaId(), result.result()); }
                catch (IOException ignored) { /* 客户端断开不触发发布恢复。 */ }
                finally { try { context.complete(); } catch (IllegalStateException ignored) { /* 连接错误可能已结束上下文。 */ } }
            }
            void fail(int status, Code code) {
                deadline.cancel();
                complete(new StorageNotificationHandler.Response(status, null, failed(deadline.stage(), code)));
            }
            void timeout() { deadline.cancel(); fail(200, Code.PROCESS_TIMEOUT); }
        }
        Completion completion = new Completion();
        timer.set(timers.schedule(completion::timeout, deadline.remainingMillis(0), TimeUnit.MILLISECONDS));
        context.addListener(new AsyncListener() {
            @Override public void onTimeout(AsyncEvent event) { completion.timeout(); }
            @Override public void onError(AsyncEvent event) { completion.fail(400, Code.INVALID_CALLBACK); }
            @Override public void onComplete(AsyncEvent event) { }
            @Override public void onStartAsync(AsyncEvent event) { }
        });
        ServletInputStream stream = request.getInputStream();
        stream.setReadListener(new ReadListener() {
            private final ByteArrayOutputStream body = new ByteArrayOutputStream();
            @Override public void onDataAvailable() {
                try {
                    byte[] buffer = new byte[4096];
                    while (!responded.get() && stream.isReady() && !stream.isFinished()) {
                        deadline.check();
                        int count = stream.read(buffer);
                        if (count < 0) { break; }
                        if (body.size() + count > handler.maxBytes()) { completion.fail(413, Code.INVALID_CALLBACK); return; }
                        body.write(buffer, 0, count);
                    }
                } catch (ProcessingDeadline.TimedOut error) { completion.fail(200, Code.PROCESS_TIMEOUT); }
                catch (IOException error) { completion.fail(400, Code.INVALID_CALLBACK); }
            }
            @Override public void onAllDataRead() {
                if (responded.get()) { return; }
                try { completion.complete(handler.handle(request, body.toByteArray(), deadline)); }
                catch (Exception error) { completion.fail(500, Code.INVALID_CALLBACK); }
            }
            @Override public void onError(Throwable error) { completion.fail(400, Code.INVALID_CALLBACK); }
        });
    }
}
