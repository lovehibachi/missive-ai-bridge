package com.lovehibachi.aichatbot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FinReplyRendererTest {
    private final FinReplyRenderer renderer = new FinReplyRenderer();

    @Test
    void preservesMarkdownListAsSemanticHtml() {
        assertEquals("<p><strong>Welcome</strong></p><ul><li>One</li><li>Two</li></ul>",
                renderer.render("**Welcome**\n\n* One\n* Two"));
    }

    @Test
    void preservesOrderedListAsSemanticHtml() {
        assertEquals("<ol><li>First</li><li>Second</li></ol>",
                renderer.render("1. First\n2. Second"));
    }

    @Test
    void keepsSafeHttpsLinksAndRemovesUnsafeHtml() {
        assertEquals("<p>Hello <strong>there</strong> <a href=\"https://example.com\">link</a></p>",
                renderer.render("<p>Hello <strong>there</strong> <a href=\"https://example.com\">link</a><script>alert(1)</script></p>"));
    }

    @Test
    void removesNonHttpsLinks() {
        assertEquals("<p>link</p>", renderer.render("<p><a href=\"http://example.com\">link</a></p>"));
    }

    @Test
    void removesIntercomCitationBeforeRendering() {
        assertEquals("<p>The final time is confirmed later.</p>",
                renderer.render("The final time is confirmed later. [<a data-inline-citation=\"\" href=\"https://intercom.help/example\">2</a>]"));
    }
}
