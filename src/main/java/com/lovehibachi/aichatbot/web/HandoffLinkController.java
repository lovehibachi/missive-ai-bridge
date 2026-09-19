package com.lovehibachi.aichatbot.web;

import com.lovehibachi.aichatbot.service.HandoffLinkService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** Public confirmation page for customer-initiated human handoff links. */
@Controller
@RequestMapping("/handoff")
public class HandoffLinkController {
    private final HandoffLinkService handoffLinkService;

    public HandoffLinkController(HandoffLinkService handoffLinkService) {
        this.handoffLinkService = handoffLinkService;
    }

    @GetMapping("/{token:[A-Za-z0-9_-]+}")
    public ResponseEntity<String> showConfirmation(@PathVariable String token) {
        HandoffLinkService.LinkState state = handoffLinkService.inspect(token);
        if (state != HandoffLinkService.LinkState.AVAILABLE) { return page(messageFor(state)); }
        return page("<h1>Talk to a human</h1><p>Would you like us to connect you with a member of our team?</p>"
                + "<form method=\"post\"><button type=\"submit\">Connect me with a human</button></form>");
    }

    @PostMapping("/{token:[A-Za-z0-9_-]+}")
    public ResponseEntity<String> confirm(@PathVariable String token) {
        HandoffLinkService.LinkState state = handoffLinkService.confirm(token);
        return page(messageFor(state));
    }

    private ResponseEntity<String> page(String content) {
        String body = "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>Love Hibachi Support</title></head><body style=\"font-family:Arial,sans-serif;max-width:520px;margin:64px auto;padding:0 24px;line-height:1.5\">"
                + content + "</body></html>";
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(body);
    }
    private String messageFor(HandoffLinkService.LinkState state) {
        if (state == HandoffLinkService.LinkState.CONFIRMED) {
            return "<h1>We’re connecting you</h1><p>A member of our team will be with you shortly. You can return to the chat window.</p>";
        }
        if (state == HandoffLinkService.LinkState.ALREADY_HANDLED) {
            return "<h1>Your request is already being handled</h1><p>Please return to the chat window. A member of our team will be with you shortly.</p>";
        }
        if (state == HandoffLinkService.LinkState.EXPIRED) {
            return "<h1>This link has expired</h1><p>Please return to the chat window and send a message to request human support.</p>";
        }
        return "<h1>This link is not available</h1><p>Please return to the chat window and send a message to request human support.</p>";
    }
}
