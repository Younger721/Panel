package com.panel.agent.domain.repository;

import com.panel.agent.domain.model.TenantAgentPromptHistory;

import java.util.List;
import java.util.Optional;

/** 门店提示词历史：追加写 + 按门店裁剪 */
public interface TenantAgentPromptHistoryRepository {

    TenantAgentPromptHistory save(TenantAgentPromptHistory history);

    /** 按版本倒序列出（用于展示与裁剪） */
    List<TenantAgentPromptHistory> findAllByAgentIdOrderByRevisionDesc(Long agentId);

    /** 只取最近若干版 */
    List<TenantAgentPromptHistory> findTop20ByAgentIdOrderByRevisionDesc(Long agentId);

    Optional<TenantAgentPromptHistory> findByAgentIdAndRevision(Long agentId, int revision);

    /** 当前最大版本号；没有任何记录时返回 0 */
    int findMaxRevision(Long agentId);

    /** 删除某个版本号之前的记录，用于保留最近 N 版 */
    void deleteByAgentIdAndRevisionLessThan(Long agentId, int revision);
}
