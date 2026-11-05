package com.nova.agent.application;

import com.nova.shared.security.CurrentActor;

import java.util.List;

public interface AgentManagementService {
    List<AgentView> list(CurrentActor actor);

    AgentView find(CurrentActor actor, Long agentId);

    AgentView create(CurrentActor actor, CreateAgentCommand command);

    AgentView update(CurrentActor actor, Long agentId, UpdateAgentCommand command);

    AgentView updatePrompt(CurrentActor actor, Long agentId, String systemPrompt);

    /** 门店提示词的历史版本（最近若干版，倒序） */
    List<PromptHistoryView> promptHistory(CurrentActor actor, Long agentId);

    /** 回滚到某个历史版本；回滚本身也会追加一条历史 */
    AgentView rollbackPrompt(CurrentActor actor, Long agentId, int revision);

    /** 平台应急清空门店补充规则；平台底座与安全红线不受影响 */
    AgentView clearPrompt(CurrentActor actor, Long agentId);

    AgentView disable(CurrentActor actor, Long agentId);

    AgentView activate(CurrentActor actor, Long agentId);
}
