package com.lovehibachi.aichatbot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FinReplyRendererTest {
    private final FinReplyRenderer renderer = new FinReplyRenderer();

    @Test
    void convertsMarkdownToMissiveSafeHtml() {
        assertEquals("<p><strong>Welcome</strong></p><ul><li>One</li><li>Two</li></ul>",
                renderer.render("**Welcome**\n\n* One\n* Two"));
    }

    @Test
    void keepsOnlyAllowedHtmlFormattingAndRemovesLinks() {
        assertEquals("<p>Hello <strong>there</strong> link</p>",
                renderer.render("<p>Hello <strong>there</strong> <a href=\"https://example.com\">link</a><script>alert(1)</script></p>"));
    }

    @Test
    void removesIntercomCitationBeforeRendering() {
        assertEquals("<p>The final time is confirmed later.</p>",
                renderer.render("The final time is confirmed later. [<a data-inline-citation=\"\" href=\"https://intercom.help/example\">2</a>]"));
    }
}
