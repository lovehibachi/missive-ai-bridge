package com.lovehibachi.aichatbot.service;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

/** Converts Fin's mixed Markdown/HTML reply bodies into safe HTML for Missive Live Chat. */
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
            "h1", "h2", "h3", "h4");

    private final Parser markdownParser = Parser.builder().build();
    private final HtmlRenderer markdownRenderer = HtmlRenderer.builder().escapeHtml(true).build();

    public String render(String rawReply) {
        if (rawReply == null || rawReply.isEmpty()) { return rawReply; }
        String withoutCitations = removeFinSourceCitations(rawReply);
        String html = HTML_TAG.matcher(withoutCitations).find()
                ? withoutCitations
                : markdownRenderer.render(markdownParser.parse(withoutCitations));
        return Jsoup.clean(html, "", ALLOWED_FORMATTING,
                new Document.OutputSettings().prettyPrint(false))
                .trim()
                // Markdown renderers add presentation-only newlines between tags.
                // Keeping one stable compact form makes the outgoing body predictable.
                .replaceAll(">\\s+<", "><");
    }

    private String removeFinSourceCitations(String reply) {
        String withoutHtmlCitations = FIN_HTML_SOURCE_CITATION.matcher(reply).replaceAll("");
        return FIN_SOURCE_CITATION.matcher(withoutHtmlCitations).replaceAll("");
    }
}
