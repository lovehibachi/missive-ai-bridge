package com.lovehibachi.aichatbot.web;

import com.lovehibachi.aichatbot.config.BridgeProperties;
import com.lovehibachi.aichatbot.service.AiResumeService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Private operator actions. Nginx exposes this only through its dedicated route. */
@RestController
@RequestMapping("/admin/conversations")
public class AdminConversationController {
    private final AiResumeService aiResumeService;
    private final BridgeProperties properties;

    public AdminConversationController(AiResumeService aiResumeService, BridgeProperties properties) {
        this.aiResumeService = aiResumeService;
        this.properties = properties;
    }

    @PostMapping("/{missiveConversationId}/resume-ai")
    public ResponseEntity<Map<String, String>> resumeAi(
            @PathVariable String missiveConversationId,
            @RequestHeader(value = "X-Bridge-Admin-Token", required = false) String token) {
        requireValidToken(token);
        try {
            AiResumeService.Result result = aiResumeService.resume(missiveConversationId);
            return ResponseEntity.ok(Collections.singletonMap("status", result.name().toLowerCase()));
        } catch (AiResumeService.ConversationNotFoundException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown Missive conversation");
        }
    }

    private void requireValidToken(String suppliedToken) {
        String configuredToken = properties.getAdmin().getToken();
        if (isBlank(configuredToken) || isBlank(suppliedToken)
                || !MessageDigest.isEqual(configuredToken.getBytes(StandardCharsets.UTF_8),
                        suppliedToken.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid admin token");
        }
    }

    private boolean isBlank(String value) { return value == null || value.trim().isEmpty(); }
}
