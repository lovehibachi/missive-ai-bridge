package com.lovehibachi.aichatbot.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * An in-process, arrival-order gate for one Fin reply per customer turn.
 *
 * This intentionally runs at webhook intake, before an event is placed on an
 * asynchronous worker. Database state remains an audit/fallback record, but
 * must not decide which of two near-simultaneous callbacks arrived first.
 */
@Service
public class FinReplyTurnGate {
    private static final Duration MAX_LOCK_AGE = Duration.ofHours(24);
    private final ConcurrentHashMap<String, Instant> lockedConversations = new ConcurrentHashMap<String, Instant>();

    /** Returns true only for the first Fin reply received after a turn reset. */
    public boolean claimFirstReply(String finConversationId) {
        return lockedConversations.putIfAbsent(finConversationId, Instant.now()) == null;
    }

    /** A real customer message has started a new Fin reply turn. */
    public void reset(String finConversationId) {
        lockedConversations.remove(finConversationId);
    }

    @Scheduled(fixedDelay = 3600000L)
    public void removeExpiredLocks() {
        Instant cutoff = Instant.now().minus(MAX_LOCK_AGE);
        for (Map.Entry<String, Instant> entry : lockedConversations.entrySet()) {
            if (entry.getValue().isBefore(cutoff)) {
                lockedConversations.remove(entry.getKey(), entry.getValue());
            }
        }
    }
}
