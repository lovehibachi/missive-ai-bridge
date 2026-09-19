package com.lovehibachi.aichatbot.domain;

import java.time.Instant;
import java.util.UUID;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

/** Stores only the SHA-256 hash of a visitor-facing human-handoff token. */
@Entity
@Table(name = "handoff_links", uniqueConstraints = @UniqueConstraint(name = "uk_handoff_link_token", columnNames = "token_hash"))
public class HandoffLink {
    @Id
    private String id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "chat_conversation_id", nullable = false)
    private ChatConversation conversation;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) { id = UUID.randomUUID().toString(); }
        if (createdAt == null) { createdAt = Instant.now(); }
    }

    public String getId() { return id; }
    public ChatConversation getConversation() { return conversation; }
    public void setConversation(ChatConversation value) { conversation = value; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String value) { tokenHash = value; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant value) { expiresAt = value; }
    public Instant getUsedAt() { return usedAt; }
    public void setUsedAt(Instant value) { usedAt = value; }
}
