package com.febrie.backend.user.web.request;

import com.febrie.backend.shared.error.ApiException;
import org.springframework.http.HttpStatus;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.ObjectMapper;

/** 文字资料采用严格字段白名单；显式区分字段缺省与提交 null。 */
public record UserEditRequest(boolean bioProvided, String bio) {
    private static final JsonFactory JSON = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    public static UserEditRequest parse(byte[] body, ObjectMapper mapper) {
        try (var parser = JSON.createParser(body)) {
            var input = mapper.readTree(parser);
            if (parser.nextToken() != null || input == null || !input.isObject()
                    || input.properties().stream().anyMatch(field -> !field.getKey().equals("bio"))) {
                throw invalid();
            }
            boolean provided = input.has("bio");
            var bio = input.get("bio");
            // 本期仅开放简介，没有可编辑字段的请求视为错误，不静默成功。
            if (!provided || (!bio.isNull() && !bio.isString())) { throw invalid(); }
            return new UserEditRequest(provided, bio.isNull() ? null : bio.asString());
        } catch (JacksonException error) {
            throw invalid();
        }
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_USER_EDIT", "仅允许提交文字资料字段bio，值须为字符串或null");
    }
}
