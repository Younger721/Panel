package com.panel.knowledge.domain.model;

import com.panel.shared.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/** 知识库问答会话：一个用户在一个知识库下的持续对话。 */
@Entity
@Table(name = "kb_chat_session",
        indexes = @Index(name = "idx_kb_chat_session_owner", columnList = "kb_id,user_id,updated_at"))
public class KbChatSession extends BaseEntity {

    private static final int MAX_TITLE = 120;

    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = MAX_TITLE)
    private String title;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected KbChatSession() {
    }

    private KbChatSession(Long kbId, Long userId, String title) {
        if (kbId == null || kbId <= 0) {
            throw new IllegalArgumentException("知识库 id 不能为空");
        }
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("用户 id 不能为空");
        }
        this.kbId = kbId;
        this.userId = userId;
        this.title = normalizeTitle(title);
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static KbChatSession create(Long kbId, Long userId, String title) {
        return new KbChatSession(kbId, userId, title);
    }

    public void retitle(String title) {
        this.title = normalizeTitle(title);
        this.updatedAt = Instant.now();
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    private static String normalizeTitle(String title) {
        String value = title == null ? "" : title.trim();
        if (value.isEmpty()) {
            value = "新的对话";
        }
        return value.length() > MAX_TITLE ? value.substring(0, MAX_TITLE) : value;
    }

    public Long kbId() {
        return kbId;
    }

    public Long userId() {
        return userId;
    }

    public String title() {
        return title;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
