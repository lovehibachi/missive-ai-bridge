package com.lovehibachi.aichatbot.web;

import com.lovehibachi.aichatbot.config.BridgeProperties;
import com.lovehibachi.aichatbot.service.WebChatNotifier;
import com.lovehibachi.aichatbot.service.WebChatService;
import java.util.List;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.server.ResponseStatusException;

/** Public API used only by the first-party browser chat. Credentials never cross this boundary. */
@RestController
@RequestMapping("/api/chat")
@Validated
public class WebChatController {
    private final WebChatService webChatService;
    private final WebChatNotifier notifier;
    private final BridgeProperties properties;

    public WebChatController(WebChatService webChatService, WebChatNotifier notifier, BridgeProperties properties) {
        this.webChatService = webChatService;
        this.notifier = notifier;
        this.properties = properties;
    }

    @GetMapping("/messages")
    public ChatResponse messages(@RequestParam("session") String session,
                                 @RequestParam(value = "after", required = false) String after,
                                 HttpServletResponse servletResponse) {
        preventCaching(servletResponse);
        return new ChatResponse(webChatService.messages(session, after));
    }

    /**
     * One browser request waits for a new message for at most 25 seconds. This
     * avoids a permanent connection while delivering human replies promptly.
     */
    @GetMapping("/events")
    public DeferredResult<ChatResponse> events(@RequestParam("session") String session,
                                                @RequestParam(value = "after", required = false) String after,
                                                HttpServletResponse servletResponse) {
        preventCaching(servletResponse);
        List<WebChatService.WebChatMessage> immediate = webChatService.messages(session, after);
        if (!immediate.isEmpty()) {
            DeferredResult<ChatResponse> result = new DeferredResult<ChatResponse>();
            result.setResult(new ChatResponse(immediate));
            return result;
        }
        final DeferredResult<ChatResponse> response = new DeferredResult<ChatResponse>(
                properties.getWebChat().getLongPollTimeoutMillis(), new ChatResponse(java.util.Collections.emptyList()));
        notifier.waitForMessage(session, response,
                () -> response.setResult(new ChatResponse(webChatService.messages(session, after))));
        return response;
    }

    /**
     * Long-poll and history responses are session-specific and change whenever
     * Missive delivers a reply. Allowing a browser or intermediary to reuse an
     * earlier empty GET response makes the chat appear stuck even though the
     * message is already stored by the bridge.
     */
    private void preventCaching(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, max-age=0, must-revalidate");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        response.setDateHeader(HttpHeaders.EXPIRES, 0L);
    }

    @PostMapping("/messages")
    public ResponseEntity<Void> send(@RequestHeader(value = "X-Chat-Session", required = false) String session,
                                     @Valid @RequestBody SendMessage request) {
        if (session == null) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing chat session"); }
        webChatService.receiveVisitorMessage(session, request.getClientMessageId(), request.getBody(), request.getBrowserTimezone());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/handoff")
    public ResponseEntity<Void> handoff(@RequestHeader(value = "X-Chat-Session", required = false) String session) {
        if (session == null) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing chat session"); }
        webChatService.requestHuman(session);
        return ResponseEntity.accepted().build();
    }

    /** Receives small client delivery diagnostics; never receives chat text. */
    @PostMapping("/diagnostics")
    public ResponseEntity<Void> diagnostics(@RequestHeader(value = "X-Chat-Session", required = false) String session,
                                            @Valid @RequestBody ClientDiagnostic request) {
        if (session == null) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing chat session"); }
        webChatService.reportClientDiagnostic(session, request.getEvent(), request.getLastMessageId(), request.getDetail());
        return ResponseEntity.accepted().build();
    }

    public static class SendMessage {
        @NotBlank
        private String body;
        private String clientMessageId;
        private String browserTimezone;
        public String getBody() { return body; }
        public void setBody(String body) { this.body = body; }
        public String getClientMessageId() { return clientMessageId; }
        public void setClientMessageId(String clientMessageId) { this.clientMessageId = clientMessageId; }
        public String getBrowserTimezone() { return browserTimezone; }
        public void setBrowserTimezone(String browserTimezone) { this.browserTimezone = browserTimezone; }
    }
    public static class ClientDiagnostic {
        @NotBlank
        private String event;
        private String lastMessageId;
        private String detail;
        public String getEvent() { return event; }
        public void setEvent(String event) { this.event = event; }
        public String getLastMessageId() { return lastMessageId; }
        public void setLastMessageId(String value) { lastMessageId = value; }
        public String getDetail() { return detail; }
        public void setDetail(String value) { detail = value; }
    }
    public static class ChatResponse {
        private final List<WebChatService.WebChatMessage> messages;
        ChatResponse(List<WebChatService.WebChatMessage> messages) { this.messages = messages; }
        public List<WebChatService.WebChatMessage> getMessages() { return messages; }
    }
}
