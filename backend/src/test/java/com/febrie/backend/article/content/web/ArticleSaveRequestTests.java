package com.febrie.backend.article.content.web;

import com.febrie.backend.article.content.web.request.ArticleSaveRequest;
import com.febrie.backend.shared.error.ApiException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** ID必须来自JSON字符串，空值只对新增分支和清封面有明确含义。 */
class ArticleSaveRequestTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private byte[] body(String prefix) {
        return ("{" + prefix + "\"title\":\"x\",\"content\":{\"schemaVersion\":1,\"doc\":{}},\"coverMediaId\":null,\"tagIds\":[]}").getBytes(StandardCharsets.UTF_8);
    }
    @Test void branchesOnlyOnAbsentOrNullId() {
        assertNull(ArticleSaveRequest.parse(body(""), mapper).articleId());
        assertNull(ArticleSaveRequest.parse(body("\"articleId\":null,"), mapper).articleId());
        assertEquals(12L, ArticleSaveRequest.parse(body("\"articleId\":\"12\","), mapper).articleId());
        for (String id : new String[]{"12", "\"\"", "\"0\"", "\"-1\"", "\"9223372036854775808\""}) {
            assertThrows(ApiException.class, () -> ArticleSaveRequest.parse(body("\"articleId\":" + id + ","), mapper));
        }
    }
    @Test void rejectsUnknownDuplicateMissingAndTrailingFields() {
        assertThrows(ApiException.class, () -> ArticleSaveRequest.parse(body("\"authorId\":\"1\","), mapper));
        assertThrows(ApiException.class, () -> ArticleSaveRequest.parse(body("\"articleId\":null,\"articleId\":\"1\","), mapper));
        assertThrows(ApiException.class, () -> ArticleSaveRequest.parse("{}".getBytes(StandardCharsets.UTF_8), mapper));
        assertThrows(ApiException.class, () -> ArticleSaveRequest.parse((new String(body(""), StandardCharsets.UTF_8) + " {}").getBytes(StandardCharsets.UTF_8), mapper));
    }
    @Test void allowsZeroOrFiveTagsButRejectsSixBeforeDeduplication() {
        String input = new String(body(""), StandardCharsets.UTF_8);
        assertTrue(ArticleSaveRequest.parse(body(""), mapper).tagIds().isEmpty());
        String five = input.replace("\"tagIds\":[]", "\"tagIds\":[\"1\",\"2\",\"3\",\"4\",\"5\"]");
        assertEquals(5, ArticleSaveRequest.parse(five.getBytes(StandardCharsets.UTF_8), mapper).tagIds().size());
        String six = input.replace("\"tagIds\":[]", "\"tagIds\":[\"1\",\"1\",\"1\",\"1\",\"1\",\"1\"]");
        assertThrows(ApiException.class, () -> ArticleSaveRequest.parse(six.getBytes(StandardCharsets.UTF_8), mapper));
    }
}
