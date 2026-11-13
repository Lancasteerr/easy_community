package com.febrie.backend.user.web;

import com.febrie.backend.shared.error.ApiException;
import com.febrie.backend.user.web.request.UserEditRequest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** 请求边界不允许Jackson的宽松类型转换及未知字段破坏部分更新语义。 */
class UserEditRequestTests {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void acceptsExplicitNullAndText() {
        var clear = parse("{\"bio\":null}");
        assertTrue(clear.bioProvided());
        assertNull(clear.bio());
        assertEquals("简介", parse("{\"bio\":\"简介\"}").bio());
        assertEquals("", parse("{\"bio\":\"\"}").bio());
    }

    @Test void rejectsMissingUnknownFieldsWrongTypesAndAmbiguousJson() {
        for (String body : new String[]{"{}", "null", "[]", "{\"bio\":1}", "{\"bio\":true}",
                "{\"bio\":[]}", "{\"bio\":{}}", "{\"bio\":null,\"userId\":\"2\"}",
                "{\"avatarMediaId\":null}", "{\"bio\":\"x\",\"role\":\"ADMIN\"}",
                "{\"bio\":null,\"bio\":\"x\"}", "{\"bio\":null} {}", "{"}) {
            assertEquals(400, assertThrows(ApiException.class, () -> parse(body)).status().value(), body);
        }
    }

    private UserEditRequest parse(String body) {
        return UserEditRequest.parse(body.getBytes(StandardCharsets.UTF_8), mapper);
    }
}
