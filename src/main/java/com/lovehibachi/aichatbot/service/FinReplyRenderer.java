package com.lovehibachi.aichatbot.service;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

/** Converts Fin's mixed Markdown/HTML reply bodies into safe Custom Channel HTML. */
@Component
public class FinReplyRenderer {
    private static final java.util.regex.Pattern FIN_SOURCE_CITATION = java.util.regex.Pattern.compile(
            "\\[\\d+\\s*<(?:https?://[^>\\]\\s]+|\\[[^\\]]+\\]\\(https?://[^)\\s]+\\))>\\]");
    private static final java.util.regex.Pattern FIN_HTML_SOURCE_CITATION = java.util.regex.Pattern.compile(
            "(?is)\\s*\\[\\s*<a(?=[^>]*\\bdata-inline-citation\\b)[^>]*>.*?</a>\\s*\\]");
    private static final java.util.regex.Pattern HTML_TAG = java.util.regex.Pattern.compile(
            "(?is)</?[a-z][a-z0-9]*(?:\\s+[^>]*)?>");
    private static final Safelist ALLOWED_FORMATTING = Safelist.none().addTags(
            "p", "br", "strong", "b", "em", "i", "ul", "ol", "li", "blockquote", "code", "pre",
            "h1", "h2", "h3", "h4", "a")
            .addAttributes("a", "href")
            .addProtocols("a", "href", "https");

    private final Parser markdownParser = Parser.builder().build();
    private final HtmlRenderer markdownRenderer = HtmlRenderer.builder().escapeHtml(true).build();

    public String render(String rawReply) {
        if (rawReply == null || rawReply.isEmpty()) { return rawReply; }
        String withoutCitations = removeFinSourceCitations(rawReply);
        String html = HTML_TAG.matcher(withoutCitations).find()
                ? withoutCitations
                : markdownRenderer.render(markdownParser.parse(withoutCitations));
        String sanitized = Jsoup.clean(html, "", ALLOWED_FORMATTING,
                new Document.OutputSettings().prettyPrint(false));
        // Normalize this before reparsing. Jsoup preserves the source newline
        // after a leading <br>, and the ChatScope message shell uses pre-wrap
        // for plain text; together they otherwise create a large visual gap.
        sanitized = sanitized.replaceAll("(?is)<p>\\s*<br\\s*/?>\\s*", "<p>")
                .replaceAll("(?is)<p>\\s*</p>", "");
        Document document = Jsoup.parseBodyFragment(sanitized);
        document.outputSettings().prettyPrint(false);
        removeEmptyParagraphSpacing(document);
        // Jsoup removes a non-HTTPS href but leaves the anchor element. Expose
        // its text without a non-functional link in the browser chat UI.
        document.select("a:not([href])").unwrap();
        return document.body().html().trim()
                // Compact whitespace only between block elements. Do not use a
                // broad >\\s+< rule: it would remove visible spaces before links.
                .replaceAll("(?is)(</?(?:p|ul|ol|li|blockquote|pre|h[1-4])>)\\s+(?=</?(?:p|ul|ol|li|blockquote|pre|h[1-4])\\b)", "$1")
                .replaceAll("(?i)<br>\\s+", "<br>");
    }

    /**
     * Fin sometimes returns HTML such as {@code <p><br>Next question</p>}.
     * That leading break is not content: in the Custom Channel it becomes a
     * conspicuous blank line inside one message bubble. Retain deliberate
     * breaks inside a paragraph, but remove leading formatting-only breaks and
     * paragraphs left empty by the cleanup.
     */
    private void removeEmptyParagraphSpacing(Document document) {
        for (Element paragraph : document.select("p")) {
            while (!paragraph.childNodes().isEmpty()) {
                Node first = paragraph.childNode(0);
                if (first instanceof TextNode && ((TextNode) first).getWholeText().trim().isEmpty()) {
                    first.remove();
                    continue;
                }
                if (first instanceof Element && "br".equals(((Element) first).tagName())) {
                    first.remove();
                    continue;
                }
                break;
            }
            if (paragraph.text().trim().isEmpty()) { paragraph.remove(); }
        }
    }

    private String removeFinSourceCitations(String reply) {
        String withoutHtmlCitations = FIN_HTML_SOURCE_CITATION.matcher(reply).replaceAll("");
        return FIN_SOURCE_CITATION.matcher(withoutHtmlCitations).replaceAll("");
    }
}
