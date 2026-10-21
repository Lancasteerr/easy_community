package com.febrie.backend.shared.web;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** 依赖失效发生在Session加载或安全鉴权阶段时也必须返回503而不是放行。 */
class DependencyBoundaryFilterTests {
    @Test
    void redisAndSqlFailuresDoNotLeakConnectionDetails() throws Exception {
        var filter = new DependencyBoundaryFilter(new ProblemResponses(JsonMapper.builder().build()));
        for (var failure : new RuntimeException[]{new RedisConnectionFailureException("secret-redis-password"),
                new DataAccessResourceFailureException("secret-jdbc-address")}) {
            var response = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("GET", "/api/users/me"), response,
                    (request, result) -> { throw failure; });
            assertEquals(503, response.getStatus());
            assertTrue(response.getContentAsString().contains("DEPENDENCY_UNAVAILABLE"));
            assertFalse(response.getContentAsString().contains("secret"));
        }
    }
}
