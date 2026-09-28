package com.lovehibachi.aichatbot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FinReplyRendererTest {
    private final FinReplyRenderer renderer = new FinReplyRenderer();

    @Test
    void convertsMarkdownToMissiveSafeHtml() {
        assertEquals("<p><strong>Welcome</strong></p><p>• One<br>• Two</p>",
                renderer.render("**Welcome**\n\n* One\n* Two"));
    }

    @Test
    void convertsOrderedListToLiveChatSafeNumberedLines() {
        assertEquals("<p>1. First<br>2. Second</p>",
                renderer.render("1. First\n2. Second"));
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
