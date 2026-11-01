package com.febrie.backend.article.application;

import com.febrie.backend.media.application.MediaService;
import com.febrie.backend.shared.error.ApiException;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 安全渲染必须从不可信JSON验证，不以客户端HTML作为兜底。 */
class ArticleContentRendererTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final MediaService media = mock(MediaService.class);
    private final ArticleContentRenderer renderer = new ArticleContentRenderer(mapper, media);
    private JsonNode content(String blocks) {
        return mapper.readTree("{\"schemaVersion\":1,\"doc\":{\"type\":\"doc\",\"content\":[" + blocks + "]}}");
    }
    @Test void escapesTextAndDerivesUnicodeSummary() {
        String text = "😀".repeat(205) + "<script>";
        var block = mapper.createObjectNode().put("type", "paragraph");
        block.putArray("content").addObject().put("type", "text").put("text", text);
        var result = renderer.prepare(" 标题 ", content(block.toString()), null, List.of(2L, 2L, 1L));
        assertEquals("标题", result.title());
        assertEquals("😀".repeat(200), result.summary());
        assertTrue(result.contentHtml().contains("&lt;script&gt;"));
        assertEquals(List.of(1L, 2L), result.tagIds());
    }
    @Test void replacesImageSourceAndDropsEventAttributes() {
        String id = "00000000-0000-0000-0000-000000000001";
        when(media.publicUrl(id)).thenReturn("https://trusted.test/" + id);
        var result = renderer.prepare("x", content("""
                {"type":"image","attrs":{"fileId":"%s","src":"javascript:evil()","onerror":"evil()","alt":"\\\"<x>"}},
                {"type":"image","attrs":{"fileId":"%s"}}
                """.formatted(id, id)), id, List.of());
        assertEquals(1, result.bodyMediaIds().size());
        assertEquals("", result.summary());
        assertFalse(result.contentJson().contains("javascript"));
        assertFalse(result.contentHtml().contains("onerror"));
        assertTrue(result.contentHtml().contains("&quot;&lt;x&gt;"));
    }
    @Test void rejectsUnsafeLinksUnknownNodesAndInvalidNesting() {
        for (String block : List.of(
                "{\"type\":\"iframe\"}",
                "{\"type\":\"paragraph\",\"content\":[{\"type\":\"image\"}]}",
                "{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"x\",\"marks\":[{\"type\":\"link\",\"attrs\":{\"href\":\"javascript:alert(1)\"}}]}]}",
                "{\"type\":\"listItem\",\"content\":[]}",
                "{\"type\":\"paragraph\"}")) {
            assertThrows(ApiException.class, () -> renderer.prepare("x", content(block), null, List.of()));
        }
    }
    @Test void boundsDepthNodeCountAndImageCount() {
        JsonNode paragraph = mapper.readTree("{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"x\"}]}");
        JsonNode deep = paragraph;
        for (int i = 0; i < 33; i++) {
            var quote = mapper.createObjectNode().put("type", "blockquote");
            quote.putArray("content").add(deep); deep = quote;
        }
        JsonNode tooDeep = content(deep.toString());
        assertThrows(ApiException.class, () -> renderer.prepare("x", tooDeep, null, List.of()));
        String repeated = String.join(",", java.util.Collections.nCopies(10_001, "{\"type\":\"paragraph\"}"));
        assertThrows(ApiException.class, () -> renderer.prepare("x", content(repeated), null, List.of()));
        String id = "00000000-0000-0000-0000-000000000001";
        when(media.publicUrl(id)).thenReturn("https://trusted.test/" + id);
        String images = String.join(",", java.util.Collections.nCopies(51, "{\"type\":\"image\",\"attrs\":{\"fileId\":\"" + id + "\"}}"));
        assertThrows(ApiException.class, () -> renderer.prepare("x", content(images), null, List.of()));
    }
    @Test void rendersListsCodeAndSafeLinks() {
        var result = renderer.prepare("x", content("""
                {"type":"orderedList","attrs":{"start":3},"content":[{"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"link","marks":[{"type":"link","attrs":{"href":"https://example.test/?a=1&b=2"}},{"type":"bold"}]}]}]}]},
                {"type":"codeBlock","attrs":{"language":"java"},"content":[{"type":"text","text":"<tag>"}]}
                """), null, List.of());
        assertTrue(result.contentHtml().contains("<ol start=\"3\">"));
        assertTrue(result.contentHtml().contains("rel=\"noopener noreferrer nofollow\""));
        assertTrue(result.contentHtml().contains("<pre><code class=\"language-java\">&lt;tag&gt;</code></pre>"));
        assertEquals("link <tag>", result.summary());
    }
}
