package com.nova.agent.domain.model;

import com.nova.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 门店提示词的历史版本。
 * <p>每次保存、回滚、清空都会追加一条，供门店自查与平台回滚；每个门店只保留最近若干版。
 */
@Entity
@Table(name = "tenant_agent_prompt_history",
        uniqueConstraints = @UniqueConstraint(name = "uk_agent_prompt_revision",
                columnNames = {"agent_id", "revision"}))
public class TenantAgentPromptHistory extends TenantScopedEntity {

    @Column(name = "agent_id", nullable = false, updatable = false)
    private Long agentId;

    /** 门店内递增的版本号，从 1 开始 */
    @Column(nullable = false, updatable = false)
    private int revision;

    @Column(name = "system_prompt", nullable = false, columnDefinition = "TEXT")
    private String systemPrompt;

    @Column(name = "changed_by_user_id")
    private Long changedByUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 16)
    private AgentPromptChangeType changeType;

    protected TenantAgentPromptHistory() {
    }

    private TenantAgentPromptHistory(Long tenantId, Long agentId, int revision, String systemPrompt,
                                     Long changedByUserId, AgentPromptChangeType changeType) {
        super(tenantId);
        if (agentId == null || agentId <= 0) throw new IllegalArgumentException("agentId must be positive");
        if (revision <= 0) throw new IllegalArgumentException("revision must be positive");
        this.agentId = agentId;
        this.revision = revision;
        this.systemPrompt = systemPrompt == null ? "" : systemPrompt;
        this.changedByUserId = changedByUserId;
        this.changeType = changeType == null ? AgentPromptChangeType.SAVE : changeType;
    }

    public static TenantAgentPromptHistory record(Long tenantId, Long agentId, int revision, String systemPrompt,
                                                  Long changedByUserId, AgentPromptChangeType changeType) {
        return new TenantAgentPromptHistory(tenantId, agentId, revision, systemPrompt, changedByUserId, changeType);
    }

    public Long getAgentId() {
        return agentId;
    }

    public int getRevision() {
        return revision;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public Long getChangedByUserId() {
        return changedByUserId;
    }

    public AgentPromptChangeType getChangeType() {
        return changeType;
    }
}
