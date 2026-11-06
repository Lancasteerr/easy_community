package com.febrie.backend.article.content.web;

import com.febrie.backend.shared.error.ApiException;
import com.febrie.backend.shared.web.ProblemResponses;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/** 在消息转换前限制实际请求字节，包括没有Content-Length的分块请求。 */
public class ArticleRequestFilter extends OncePerRequestFilter {
    private static final int MAX_BYTES = 1_048_576;
    private final ProblemResponses problems;
    public ArticleRequestFilter(ProblemResponses problems) { this.problems = problems; }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getServletPath();
        boolean article = path.equals("/api/articles") || path.startsWith("/api/articles/")
                || path.equals("/api/users/me/articles") || path.equals("/api/tags") || path.equals("/api/admin/tags");
        if (!article) { chain.doFilter(request, response); return; }
        response.setHeader("Cache-Control", "no-store");
        String requestId = UUID.randomUUID().toString();
        response.setHeader("X-Request-Id", requestId);
        try (var ignored = MDC.putCloseable("requestId", requestId)) {
            filterBody(request, response, chain, path);
        }
    }

    private void filterBody(HttpServletRequest request, HttpServletResponse response, FilterChain chain, String path)
            throws ServletException, IOException {
        if (!path.equals("/api/articles") || !request.getMethod().equals("POST")) {
            chain.doFilter(request, response); return;
        }
        if (request.getContentLengthLong() > MAX_BYTES) { tooLarge(response); return; }
        byte[] bytes = request.getInputStream().readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) { tooLarge(response); return; }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                ByteArrayInputStream input = new ByteArrayInputStream(bytes);
                return new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public int read(byte[] target, int off, int len) { return input.read(target, off, len); }
                    @Override public boolean isFinished() { return input.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new IllegalStateException("文章入口仅同步读取请求体"); }
                };
            }
            @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8)); }
        }, response);
    }
    private void tooLarge(HttpServletResponse response) throws IOException {
        problems.write(response, new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "ARTICLE_TOO_LARGE", "文章请求不能超过1MiB"));
    }
}
