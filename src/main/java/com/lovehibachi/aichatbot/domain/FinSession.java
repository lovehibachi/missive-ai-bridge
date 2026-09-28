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

@Entity
@Table(name = "fin_sessions", uniqueConstraints = @UniqueConstraint(name = "uk_fin_session_external", columnNames = "fin_conversation_id"))
public class FinSession {
    @Id
    private String id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "chat_conversation_id", nullable = false)
    private ChatConversation conversation;

    @Column(name = "fin_conversation_id", nullable = false, length = 255)
    private String finConversationId;

    @Column(name = "cycle_number", nullable = false)
    private int cycleNumber;

    @Column(nullable = false, length = 64)
    private String status;

    @Column(name = "reply_buffer", columnDefinition = "text")
    private String replyBuffer;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "reply_received_at")
    private Instant replyReceivedAt;

    @Column(name = "first_reply_sent_at")
    private Instant firstReplySentAt;

    @Column(name = "low_peak_follow_up_sent_at")
    private Instant lowPeakFollowUpSentAt;

    /** Greeting selected when the visitor began this turn; delivered before Fin's first reply. */
    @Column(name = "greeting_html", columnDefinition = "text")
    private String greetingHtml;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) { id = UUID.randomUUID().toString(); }
        createdAt = Instant.now();
    }

    public String getId() { return id; }
    public ChatConversation getConversation() { return conversation; }
    public void setConversation(ChatConversation value) { this.conversation = value; }
    public String getFinConversationId() { return finConversationId; }
    public void setFinConversationId(String value) { this.finConversationId = value; }
    public int getCycleNumber() { return cycleNumber; }
    public void setCycleNumber(int value) { this.cycleNumber = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { this.status = value; }
    public String getReplyBuffer() { return replyBuffer; }
    public void setReplyBuffer(String value) { this.replyBuffer = value; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant value) { this.completedAt = value; }
    public Instant getReplyReceivedAt() { return replyReceivedAt; }
    public void setReplyReceivedAt(Instant value) { this.replyReceivedAt = value; }
    public Instant getFirstReplySentAt() { return firstReplySentAt; }
    public void setFirstReplySentAt(Instant value) { firstReplySentAt = value; }
    public Instant getLowPeakFollowUpSentAt() { return lowPeakFollowUpSentAt; }
    public void setLowPeakFollowUpSentAt(Instant value) { lowPeakFollowUpSentAt = value; }
    public String getGreetingHtml() { return greetingHtml; }
    public void setGreetingHtml(String value) { greetingHtml = value; }
}
