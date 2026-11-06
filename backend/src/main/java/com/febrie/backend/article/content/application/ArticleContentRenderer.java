package com.febrie.backend.article.content.application;

import com.febrie.backend.article.common.ArticleErrors;

import com.febrie.backend.article.content.application.dto.PreparedArticle;
import com.febrie.backend.media.application.MediaUrls;
import com.febrie.backend.media.domain.MediaIds;
import java.net.URI;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;

/** 只解释固定正文schema；HTML、摘要与引用均由同一次规范化遍历派生。 */
@Component
public class ArticleContentRenderer {
    private static final Set<String> BLOCKS = Set.of("paragraph", "heading", "blockquote", "bulletList",
            "orderedList", "codeBlock", "image", "horizontalRule");
    private static final Map<String, String> MARK_TAGS = Map.of("bold", "strong", "italic", "em",
            "strike", "s", "underline", "u", "code", "code");
    private final ObjectMapper mapper;
    private final MediaUrls urls;

    public ArticleContentRenderer(ObjectMapper mapper, MediaUrls urls) { this.mapper = mapper; this.urls = urls; }

    public PreparedArticle prepare(String title, JsonNode content, String coverId, List<Long> tags) {
        String normalizedTitle = title == null ? "" : title.strip();
        if (normalizedTitle.isBlank() || length(normalizedTitle) > 100 || tags.size() > 5) { throw ArticleErrors.invalid(); }
        requireFields(content, Set.of("schemaVersion", "doc"));
        if (!content.path("schemaVersion").isIntegralNumber() || content.path("schemaVersion").asInt(-1) != 1) {
            throw ArticleErrors.invalid();
        }
        State state = new State();
        Rendered rendered = node(content.get("doc"), null, 1, state);
        if (!"doc".equals(rendered.json().path("type").asString())) { throw ArticleErrors.invalid(); }
        String plain = state.plain.toString().replaceAll("[\\s\\p{Z}]+", " ").strip();
        if (plain.isEmpty() && state.images.isEmpty()) { throw ArticleErrors.invalid(); }
        String summary = plain.substring(0, plain.offsetByCodePoints(0, Math.min(200, length(plain))));
        var normalized = mapper.createObjectNode().put("schemaVersion", 1).set("doc", rendered.json());
        return new PreparedArticle(normalizedTitle, summary, mapper.writeValueAsString(normalized), rendered.html(),
                coverId == null ? null : mediaId(coverId), state.images, new ArrayList<>(new TreeSet<>(tags)));
    }

    private Rendered node(JsonNode input, String parent, int depth, State state) {
        if (depth > 32 || ++state.nodes > 10_000) { throw ArticleErrors.invalid(); }
        requireFields(input, Set.of("type", "attrs", "content", "marks", "text"));
        String type = string(input.get("type"));
        requireParent(type, parent);
        ObjectNode output = mapper.createObjectNode().put("type", type);
        if (input.has("attrs") && !input.get("attrs").isObject()) { throw ArticleErrors.invalid(); }
        if (!type.equals("text") && (input.has("text") || input.has("marks"))) { throw ArticleErrors.invalid(); }
        if (type.equals("text")) { return text(input, output, parent, state); }
        if (type.equals("image")) { return image(input, output, state); }
        if (type.equals("hardBreak") || type.equals("horizontalRule")) {
            noChildren(input);
            state.plain.append('\n');
            return new Rendered(output, type.equals("hardBreak") ? "<br>" : "<hr>");
        }

        JsonNode children = input.get("content");
        if (children == null) { children = mapper.createArrayNode(); }
        if (!children.isArray()) { throw ArticleErrors.invalid(); }
        if (Set.of("doc", "blockquote", "bulletList", "orderedList", "listItem").contains(type) && children.isEmpty()) {
            throw ArticleErrors.invalid();
        }
        if (type.equals("listItem") && !"paragraph".equals(children.get(0).path("type").asString())) {
            throw ArticleErrors.invalid();
        }
        String tag = switch (type) {
            case "doc" -> "";
            case "paragraph" -> "p";
            case "heading" -> "h" + integerAttr(input, "level", 1, 6, 1);
            case "blockquote" -> "blockquote";
            case "bulletList" -> "ul";
            case "orderedList" -> "ol";
            case "listItem" -> "li";
            case "codeBlock" -> "pre";
            default -> throw ArticleErrors.invalid();
        };
        String attributes = "";
        String language = null;
        if (type.equals("heading")) { output.putObject("attrs").put("level", Integer.parseInt(tag.substring(1))); }
        if (type.equals("orderedList")) {
            int start = integerAttr(input, "start", 1, Integer.MAX_VALUE, 1);
            output.putObject("attrs").put("start", start);
            attributes = " start=\"" + start + "\"";
        }
        if (type.equals("codeBlock")) {
            JsonNode value = input.path("attrs").get("language");
            if (value != null && !value.isNull()) {
                language = string(value);
                if (!language.matches("[A-Za-z0-9_+\\-]{1,32}")) { throw ArticleErrors.invalid(); }
                output.putObject("attrs").put("language", language);
            }
        }
        ArrayNode normalizedChildren = output.putArray("content");
        StringBuilder html = new StringBuilder();
        if (!tag.isEmpty()) { html.append('<').append(tag).append(attributes).append('>'); }
        if (type.equals("codeBlock")) {
            html.append("<code");
            if (language != null) { html.append(" class=\"language-").append(language).append('"'); }
            html.append('>');
        }
        for (JsonNode child : children) {
            Rendered result = node(child, type, depth + 1, state);
            normalizedChildren.add(result.json());
            html.append(result.html());
        }
        if (type.equals("codeBlock")) { html.append("</code>"); }
        if (!tag.isEmpty()) { html.append("</").append(tag).append('>'); state.plain.append('\n'); }
        return new Rendered(output, html.toString());
    }

    private Rendered text(JsonNode input, ObjectNode output, String parent, State state) {
        if (input.has("content") || input.has("attrs")) { throw ArticleErrors.invalid(); }
        String value = string(input.get("text"));
        if (value.isEmpty()) { throw ArticleErrors.invalid(); }
        output.put("text", value);
        state.plain.append(value);
        Map<String, ObjectNode> marks = new TreeMap<>();
        JsonNode supplied = input.get("marks");
        if (supplied != null) {
            if (!supplied.isArray() || supplied.size() > 6) { throw ArticleErrors.invalid(); }
            for (JsonNode mark : supplied) {
                requireFields(mark, Set.of("type", "attrs"));
                String type = string(mark.get("type"));
                if ((!MARK_TAGS.containsKey(type) && !type.equals("link")) || marks.containsKey(type)) { throw ArticleErrors.invalid(); }
                ObjectNode normalized = mapper.createObjectNode().put("type", type);
                if (type.equals("link")) { normalized.putObject("attrs").put("href", link(mark.path("attrs").get("href"))); }
                marks.put(type, normalized);
            }
        }
        if ((parent.equals("codeBlock") && !marks.isEmpty()) || (marks.containsKey("code") && marks.size() != 1)) {
            throw ArticleErrors.invalid();
        }
        String html = escape(value);
        if (!marks.isEmpty()) {
            ArrayNode normalized = output.putArray("marks");
            for (var entry : marks.entrySet()) {
                normalized.add(entry.getValue());
                if (entry.getKey().equals("link")) {
                    html = "<a href=\"" + escape(entry.getValue().path("attrs").path("href").asString())
                            + "\" rel=\"noopener noreferrer nofollow\">" + html + "</a>";
                } else {
                    String tag = MARK_TAGS.get(entry.getKey());
                    html = "<" + tag + ">" + html + "</" + tag + ">";
                }
            }
        }
        return new Rendered(output, html);
    }

    private Rendered image(JsonNode input, ObjectNode output, State state) {
        noChildren(input);
        if (++state.imageNodes > 50) { throw ArticleErrors.invalid(); }
        String id = mediaId(string(input.path("attrs").get("fileId")));
        state.images.add(id);
        String url = urls.publicUrl(id);
        String alt = optionalString(input.path("attrs").get("alt"));
        String title = optionalString(input.path("attrs").get("title"));
        output.putObject("attrs").put("fileId", id).put("src", url).put("alt", alt).put("title", title);
        return new Rendered(output, "<img src=\"" + escape(url) + "\" data-file-id=\"" + id
                + "\" alt=\"" + escape(alt) + "\" title=\"" + escape(title) + "\">");
    }

    private static void requireParent(String type, String parent) {
        boolean allowed = parent == null ? type.equals("doc") : switch (parent) {
            case "doc", "blockquote", "listItem" -> BLOCKS.contains(type);
            case "paragraph", "heading" -> type.equals("text") || type.equals("hardBreak");
            case "bulletList", "orderedList" -> type.equals("listItem");
            case "codeBlock" -> type.equals("text");
            default -> false;
        };
        if (!allowed) { throw ArticleErrors.invalid(); }
    }

    public static void requireFields(JsonNode value, Set<String> allowed) {
        if (value == null || !value.isObject() || !allowed.containsAll(value.propertyNames())) { throw ArticleErrors.invalid(); }
    }
    public static String string(JsonNode value) {
        if (value == null || !value.isString()) { throw ArticleErrors.invalid(); }
        return value.asString();
    }
    private static String optionalString(JsonNode value) {
        String result = value == null || value.isNull() ? "" : string(value);
        if (length(result) > 500) { throw ArticleErrors.invalid(); }
        return result;
    }
    private static String mediaId(String value) {
        try { return MediaIds.canonical(value); }
        catch (IllegalArgumentException error) { throw ArticleErrors.invalid(); }
    }
    private static int integerAttr(JsonNode node, String name, int min, int max, int fallback) {
        JsonNode value = node.path("attrs").get(name);
        if (value == null || value.isNull()) { return fallback; }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) { throw ArticleErrors.invalid(); }
        int result = value.asInt();
        if (result < min || result > max) { throw ArticleErrors.invalid(); }
        return result;
    }
    private static String link(JsonNode value) {
        String href = string(value);
        try {
            URI uri = URI.create(href);
            if (href.length() > 2048 || uri.getHost() == null || uri.getUserInfo() != null
                    || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) {
                throw ArticleErrors.invalid();
            }
            return href;
        } catch (IllegalArgumentException error) { throw ArticleErrors.invalid(); }
    }
    private static void noChildren(JsonNode value) {
        if (value.has("content")) { throw ArticleErrors.invalid(); }
    }
    private static int length(String value) { return value.codePointCount(0, value.length()); }
    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
    private record Rendered(ObjectNode json, String html) { }
    private static final class State {
        private int nodes;
        private int imageNodes;
        private final Set<String> images = new TreeSet<>();
        private final StringBuilder plain = new StringBuilder();
    }
}
