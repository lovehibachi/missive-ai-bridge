package com.lovehibachi.aichatbot.service;

import java.util.ArrayList;
import java.util.List;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.parser.Tag;
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
        String sanitized = Jsoup.clean(html, "", ALLOWED_FORMATTING,
                new Document.OutputSettings().prettyPrint(false));
        Document document = Jsoup.parseBodyFragment(sanitized);
        replaceListsWithLiveChatSafeBullets(document.body());
        return document.body().html().trim()
                // Markdown renderers add presentation-only newlines between tags.
                // Keeping one stable compact form makes the outgoing body predictable.
                .replaceAll(">\\s+<", "><")
                .replaceAll("(?i)<br>\\s+", "<br>");
    }

    private void replaceListsWithLiveChatSafeBullets(Element body) {
        /*
         * Missive Live Chat turns <li> elements into literal '*' characters.
         * Use plain Unicode markers plus <br> instead, which the widget renders
         * consistently while still preserving the visual structure of the list.
         */
        List<Element> lists = new ArrayList<Element>(body.select("ul, ol"));
        for (Element list : lists) {
            Element replacement = new Element(Tag.valueOf("p"), "");
            boolean ordered = "ol".equals(list.tagName());
            int position = 1;
            for (Element item : list.children()) {
                if (!"li".equals(item.tagName())) { continue; }
                if (!replacement.childNodes().isEmpty()) { replacement.appendElement("br"); }
                replacement.appendText(ordered ? position++ + ". " : "• ");
                replacement.appendChildren(new ArrayList<Node>(item.childNodes()));
            }
            list.replaceWith(replacement);
        }
    }

    private String removeFinSourceCitations(String reply) {
        String withoutHtmlCitations = FIN_HTML_SOURCE_CITATION.matcher(reply).replaceAll("");
        return FIN_SOURCE_CITATION.matcher(withoutHtmlCitations).replaceAll("");
    }
}
