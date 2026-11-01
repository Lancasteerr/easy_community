package com.febrie.backend.article.web.request;

import com.febrie.backend.article.application.*;
import com.febrie.backend.article.application.dto.SaveArticleCommand;
import java.util.*;
import tools.jackson.core.*;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.*;

/** 此入口独立严格解析，避免全局字符串强制转换把JSON数值ID误认为合法字符串。 */
public final class ArticleSaveRequest {
    private static final JsonFactory JSON = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(128).maxStringLength(1_048_576).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private ArticleSaveRequest() { }

    public static SaveArticleCommand parse(byte[] body, ObjectMapper mapper) {
        JsonNode input;
        try (var parser = JSON.createParser(body)) {
            input = mapper.readTree(parser);
            if (parser.nextToken() != null) { throw ArticleErrors.invalid(); }
        } catch (JacksonException error) { throw ArticleErrors.invalid(); }
        ArticleContentRenderer.requireFields(input, Set.of("articleId", "title", "content", "coverMediaId", "tagIds"));
        for (String field : List.of("title", "content", "coverMediaId", "tagIds")) {
            if (!input.has(field)) { throw ArticleErrors.invalid(); }
        }
        Long id = input.hasNonNull("articleId") ? ArticleIds.parse(ArticleContentRenderer.string(input.get("articleId"))) : null;
        String cover = input.get("coverMediaId").isNull() ? null : ArticleContentRenderer.string(input.get("coverMediaId"));
        JsonNode tagIds = input.get("tagIds");
        if (!tagIds.isArray() || tagIds.size() > 5) { throw ArticleErrors.invalid(); }
        List<Long> tags = new ArrayList<>();
        for (JsonNode tag : tagIds) { tags.add(ArticleIds.parse(ArticleContentRenderer.string(tag))); }
        return new SaveArticleCommand(id, ArticleContentRenderer.string(input.get("title")), input.get("content"), cover, tags);
    }
}
