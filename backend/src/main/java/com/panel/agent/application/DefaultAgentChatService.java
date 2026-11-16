package com.panel.agent.application;

import com.panel.agent.domain.model.AgentStatus;
import com.panel.agent.domain.model.TenantAgent;
import com.panel.agent.domain.repository.TenantAgentRepository;
import com.panel.ai.application.AiChatView;
import com.panel.ai.harness.application.AiHarnessService;
import com.panel.shared.error.BusinessException;
import com.panel.shared.error.ErrorCode;
import com.panel.shared.security.ActorAccess;
import com.panel.shared.security.CurrentActor;
import org.springframework.stereotype.Service;

/**
 * 门店助手对话入口。
 *
 * <p>只做三件事：校验门店身份与准入、确认 Agent 存在且启用、把问题交给统一内核（Harness）。
 * 提示词分层、知识检索、工具编排、用量记账都在 Harness 里，门店入口不再各自实现一套，
 * 否则两个入口的回答质量、权限边界和提示词拼法会长期漂移。</p>
 */
@Service
public class DefaultAgentChatService implements AgentChatService {

    private final TenantAgentRepository agents;
    private final AiHarnessService harness;

    public DefaultAgentChatService(TenantAgentRepository agents, AiHarnessService harness) {
        this.agents = agents;
        this.harness = harness;
    }

    @Override
    public AiChatView chat(CurrentActor actor, Long agentId, String sessionId, String question) {
        Long tenantId = ActorAccess.tenantId(actor);
        if (tenantId == null || !(actor.hasPermission("menu:assistant") || actor.hasPermission("agent:chat"))) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (agentId == null || sessionId == null || sessionId.isBlank()
                || question == null || question.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        TenantAgent agent = agents.findByTenantIdAndId(tenantId, agentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (agent.getStatus() != AgentStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        // 门店入口与平台助手共用同一个内核：平台底座 → 门店补充 → 安全红线，工具编排与用量也由内核统一处理
        return harness.chat(actor, sessionId, question, null);
    }
}
