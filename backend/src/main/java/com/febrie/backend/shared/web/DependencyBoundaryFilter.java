package com.febrie.backend.shared.web;

import com.febrie.backend.shared.error.ApiException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/** 包围SessionRepositoryFilter，覆盖安全上下文加载及会话落盘阶段的依赖故障。 */
public class DependencyBoundaryFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(DependencyBoundaryFilter.class);
    private final ProblemResponses problems;
    public DependencyBoundaryFilter(ProblemResponses problems) { this.problems = problems; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (Exception error) {
            ApiException exposed = null;
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                if (cause instanceof ApiException api) { exposed = api; break; }
                if (cause instanceof DataAccessException) {
                    exposed = new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", "服务暂不可用，请稍后重试");
                    break;
                }
            }
            if (exposed == null) {
                log.error("请求处理失败：{}", error.getClass().getSimpleName());
                exposed = new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "请求暂时无法完成");
            }
            problems.write(response, exposed);
        }
    }
}
