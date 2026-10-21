package com.febrie.backend.shared.web;

import com.febrie.backend.shared.error.ApiException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** MVC与安全过滤器共享错误格式，响应中只包含预先定义的安全描述。 */
@Component
public class ProblemResponses {
    private final ObjectMapper mapper;
    public ProblemResponses(ObjectMapper mapper) { this.mapper = mapper; }

    public static ProblemDetail body(ApiException error) {
        var body = ProblemDetail.forStatusAndDetail(error.status(), error.getMessage());
        body.setProperty("code", error.code());
        return body;
    }

    public void write(HttpServletResponse response, ApiException error) throws IOException {
        if (response.isCommitted()) { return; }
        response.setStatus(error.status().value());
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        if (error.retryAfter() > 0) {
            response.setHeader("Retry-After", Long.toString(error.retryAfter()));
        }
        mapper.writeValue(response.getOutputStream(), body(error));
    }
}
