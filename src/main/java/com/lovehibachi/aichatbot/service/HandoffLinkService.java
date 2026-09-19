package com.lovehibachi.aichatbot.service;

import com.lovehibachi.aichatbot.config.BridgeProperties;
import com.lovehibachi.aichatbot.domain.ChatConversation;
import com.lovehibachi.aichatbot.domain.ConversationState;
import com.lovehibachi.aichatbot.domain.HandoffLink;
import com.lovehibachi.aichatbot.repository.HandoffLinkRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

@Service
public class HandoffLinkService {
    private static final Logger LOGGER = LoggerFactory.getLogger(HandoffLinkService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private final HandoffLinkRepository linkRepository;
    private final HandoffService handoffService;
    private final BridgeProperties properties;

    public HandoffLinkService(HandoffLinkRepository linkRepository,
                              HandoffService handoffService,
                              BridgeProperties properties) {
        this.linkRepository = linkRepository;
        this.handoffService = handoffService;
        this.properties = properties;
    }

    /**
     * Adds a visually secondary human-handoff link to a normal Fin reply. The
     * opaque token is stored only as a hash, so a database read cannot be used
     * to impersonate a visitor and request a handoff for their conversation.
     */
    @Transactional
    public String appendToFinReply(ChatConversation conversation, String answerHtml) {
        String token = newToken();
        HandoffLink link = new HandoffLink();
        link.setConversation(conversation);
        link.setTokenHash(sha256(token));
        link.setExpiresAt(Instant.now().plusSeconds(properties.getHandoffLinks().getTtlMinutes() * 60L));
        linkRepository.save(link);
        String href = publicBaseUrl() + "/handoff/" + token;
        return answerHtml + "<p><small>Need more help? <a href=\"" + HtmlUtils.htmlEscape(href)
                + "\" target=\"_blank\" rel=\"noopener noreferrer\">Talk to a human</a></small></p>";
    }

    @Transactional(readOnly = true)
    public LinkState inspect(String rawToken) {
        Optional<HandoffLink> link = linkRepository.findByTokenHash(sha256(rawToken));
        return link.isPresent() ? stateOf(link.get(), Instant.now()) : LinkState.INVALID;
    }

    /** Consumes the one-time link and delegates to the normal, idempotent handoff flow. */
    @Transactional
    public LinkState confirm(String rawToken) {
        Optional<HandoffLink> found = linkRepository.findByTokenHashForUpdate(sha256(rawToken));
        if (!found.isPresent()) { return LinkState.INVALID; }
        HandoffLink link = found.get();
        LinkState state = stateOf(link, Instant.now());
        if (state != LinkState.AVAILABLE) { return state; }
        link.setUsedAt(Instant.now());
        linkRepository.save(link);
        handoffService.requestHuman(link.getConversation(), "Visitor clicked human handoff link");
        LOGGER.info("Visitor confirmed human handoff link: missiveConversationId={}",
                link.getConversation().getMissiveConversationId());
        return LinkState.CONFIRMED;
    }

    @Scheduled(fixedDelay = 86400000L)
    @Transactional
    public void removeExpiredLinks() {
        long removed = linkRepository.deleteByExpiresAtBefore(Instant.now());
        if (removed > 0) { LOGGER.info("Removed expired human handoff links: count={}", removed); }
    }

    private LinkState stateOf(HandoffLink link, Instant now) {
        if (link.getUsedAt() != null || link.getConversation().getState() != ConversationState.AI_HANDLING) {
            return LinkState.ALREADY_HANDLED;
        }
        return link.getExpiresAt().isAfter(now) ? LinkState.AVAILABLE : LinkState.EXPIRED;
    }
    private String newToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    private String publicBaseUrl() {
        String value = properties.getHandoffLinks().getPublicBaseUrl();
        if (value == null || value.trim().isEmpty()) { throw new IllegalStateException("Missing BRIDGE_PUBLIC_BASE_URL"); }
        return value.replaceAll("/+$", "");
    }
    private String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte part : hash) { result.append(String.format("%02x", part & 0xff)); }
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash handoff token", exception);
        }
    }

    public enum LinkState { AVAILABLE, CONFIRMED, ALREADY_HANDLED, EXPIRED, INVALID }
}
