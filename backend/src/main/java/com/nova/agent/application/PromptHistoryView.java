package com.nova.agent.application;

import com.nova.agent.domain.model.AgentPromptChangeType;
import com.nova.agent.domain.model.TenantAgentPromptHistory;

import java.time.Instant;

/** 门店提示词历史版本视图 */
public record PromptHistoryView(
        int revision,
        String systemPrompt,
        AgentPromptChangeType changeType,
        Long changedByUserId,
        Instant createdAt) {

    public static PromptHistoryView from(TenantAgentPromptHistory history) {
        return new PromptHistoryView(
                history.getRevision(),
                history.getSystemPrompt(),
                history.getChangeType(),
                history.getChangedByUserId(),
                history.getCreatedAt());
    }
}
