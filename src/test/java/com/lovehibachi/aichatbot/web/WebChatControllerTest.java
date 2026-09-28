package com.lovehibachi.aichatbot.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lovehibachi.aichatbot.config.BridgeProperties;
import com.lovehibachi.aichatbot.service.WebChatNotifier;
import com.lovehibachi.aichatbot.service.WebChatService;
import java.util.Collections;
import javax.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class WebChatControllerTest {
    @Test
    void historyAndLongPollResponsesAreNeverCached() {
        WebChatService webChatService = mock(WebChatService.class);
        WebChatNotifier notifier = mock(WebChatNotifier.class);
        HttpServletResponse historyResponse = mock(HttpServletResponse.class);
        HttpServletResponse eventsResponse = mock(HttpServletResponse.class);
        when(webChatService.messages("session-1", null)).thenReturn(Collections.emptyList());
        BridgeProperties properties = new BridgeProperties();
        WebChatController controller = new WebChatController(webChatService, notifier, properties);

        controller.messages("session-1", null, historyResponse);
        controller.events("session-1", null, eventsResponse);

        verifyNoStoreHeaders(historyResponse);
        verifyNoStoreHeaders(eventsResponse);
    }

    private void verifyNoStoreHeaders(HttpServletResponse response) {
        verify(response).setHeader(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, max-age=0, must-revalidate");
        verify(response).setHeader(HttpHeaders.PRAGMA, "no-cache");
        verify(response).setDateHeader(HttpHeaders.EXPIRES, 0L);
    }
}
