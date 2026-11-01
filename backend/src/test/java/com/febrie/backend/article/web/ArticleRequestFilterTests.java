package com.febrie.backend.article.web;

import com.febrie.backend.shared.web.ProblemResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** 未声明长度的实际流也受上限保护，允许范围内请求体必须仍能被Controller读取。 */
class ArticleRequestFilterTests {
    private final ArticleRequestFilter filter = new ArticleRequestFilter(new ProblemResponses(new ObjectMapper()));
    @Test void limitsChunkedBodyBeforeConversion() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/articles") {
            @Override public long getContentLengthLong() { return -1; }
        };
        request.setServletPath("/api/articles");
        request.setContent(new byte[1_048_577]);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> fail("超量正文不能进入Controller"));
        assertEquals(413, response.getStatus());
        assertEquals("no-store", response.getHeader("Cache-Control"));
    }
    @Test void preservesBodyAndNoStore() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/articles");
        request.setServletPath("/api/articles"); request.setContent(new byte[]{1, 2, 3});
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> assertArrayEquals(new byte[]{1, 2, 3}, ((HttpServletRequest) req).getInputStream().readAllBytes()));
        assertEquals("no-store", response.getHeader("Cache-Control"));
    }
}
