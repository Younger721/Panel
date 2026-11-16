package com.panel.knowledge.domain.model;

import com.panel.shared.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/** 一条问答消息；引用快照以 JSON 保存，避免文档被改后引用内容漂移。 */
@Entity
@Table(name = "kb_chat_message",
        uniqueConstraints = @UniqueConstraint(name = "uk_kb_chat_message_seq", columnNames = {"session_id", "sequence_no"}),
        indexes = @Index(name = "idx_kb_chat_message_session", columnList = "session_id,id"))
public class KbChatMessage extends BaseEntity {

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, columnDefinition = "varchar(16)")
    private KbChatRole role;

    @Column(nullable = false, columnDefinition = "longtext")
    private String content;

    @Column(name = "citations_json", columnDefinition = "longtext")
    private String citationsJson;

    /** 本次回答的思考链路快照，重进会话仍能回放。 */
    @Column(name = "stages_json", columnDefinition = "longtext")
    private String stagesJson;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "sequence_no", nullable = false)
    private int sequenceNo;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected KbChatMessage() {
    }

    private KbChatMessage(Long sessionId, KbChatRole role, String content, String citationsJson,
                          String stagesJson, Integer latencyMs, int sequenceNo) {
        this.sessionId = sessionId;
        this.role = role;
        this.content = content;
        this.citationsJson = citationsJson;
        this.stagesJson = stagesJson;
        this.latencyMs = latencyMs;
        this.sequenceNo = sequenceNo;
        this.createdAt = Instant.now();
    }

    public static KbChatMessage user(Long sessionId, String content, int sequenceNo) {
        return new KbChatMessage(sessionId, KbChatRole.USER, content, null, null, null, sequenceNo);
    }

    public static KbChatMessage assistant(Long sessionId, String content, String citationsJson,
                                          String stagesJson, Integer latencyMs, int sequenceNo) {
        return new KbChatMessage(sessionId, KbChatRole.ASSISTANT, content, citationsJson,
                stagesJson, latencyMs, sequenceNo);
    }

    public Long sessionId() {
        return sessionId;
    }

    public KbChatRole role() {
        return role;
    }

    public String content() {
        return content;
    }

    public String citationsJson() {
        return citationsJson;
    }

    public String stagesJson() {
        return stagesJson;
    }

    public Integer latencyMs() {
        return latencyMs;
    }

    public int sequenceNo() {
        return sequenceNo;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
