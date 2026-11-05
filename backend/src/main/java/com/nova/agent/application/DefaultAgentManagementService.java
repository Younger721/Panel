package com.nova.agent.application;

import com.nova.agent.domain.model.AgentPromptChangeType;
import com.nova.agent.domain.model.AgentStatus;
import com.nova.agent.domain.model.TenantAgent;
import com.nova.agent.domain.model.TenantAgentPromptHistory;
import com.nova.agent.domain.repository.TenantAgentPromptHistoryRepository;
import com.nova.agent.domain.repository.TenantAgentRepository;
import com.nova.audit.application.AuditRecorder;
import com.nova.audit.domain.AuditEvent;
import com.nova.audit.domain.AuditResult;
import com.nova.iam.domain.model.RoleCode;
import com.nova.shared.error.BusinessException;
import com.nova.shared.error.ErrorCode;
import com.nova.shared.security.CurrentActor;
import com.nova.shared.security.ActorAccess;
import com.nova.tenancy.application.TenantQuotaService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Comparator;

@Service
public class DefaultAgentManagementService implements AgentManagementService {

    /** 每个门店保留最近若干版提示词历史 */
    private static final int PROMPT_HISTORY_LIMIT = 20;

    private final TenantAgentRepository agents;
    private final TenantQuotaService quotas;
    private final AuditRecorder audit;
    /** 提示词历史（可选）：单测中允许为空，为空时不影响保存主流程 */
    private final TenantAgentPromptHistoryRepository promptHistory;

    public DefaultAgentManagementService(
            TenantAgentRepository agents,
            TenantQuotaService quotas,
            AuditRecorder audit) {
        this(agents, quotas, audit, null);
    }

    @Autowired
    public DefaultAgentManagementService(
            TenantAgentRepository agents,
            TenantQuotaService quotas,
            AuditRecorder audit,
            TenantAgentPromptHistoryRepository promptHistory) {
        this.agents = agents;
        this.quotas = quotas;
        this.audit = audit;
        this.promptHistory = promptHistory;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentView> list(CurrentActor actor) {
        if (canBrowseAllTenants(actor)) {
            return agents.findTop200ByOrderByIdDesc().stream().map(AgentView::from).toList();
        }
        requireTenant(actor);
        Long tenantId = ActorAccess.tenantId(actor);
        if (canManage(actor)) {
            return agents.findAllByTenantId(tenantId).stream().map(AgentView::from).toList();
        }
        if (!hasAgentMenu(actor)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return agents.findAllByTenantIdAndStatus(tenantId, AgentStatus.ACTIVE).stream()
                .map(AgentView::from)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public AgentView find(CurrentActor actor, Long agentId) {
        requireTenant(actor);
        Long tenantId = ActorAccess.tenantId(actor);
        TenantAgent agent = getScoped(actor, agentId);
        if (!canManage(actor) && agent.getStatus() != AgentStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return AgentView.from(agent);
    }

    @Override
    @Transactional
    public AgentView create(CurrentActor actor, CreateAgentCommand command) {
        throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    @Override
    @Transactional
    public AgentView update(CurrentActor actor, Long agentId, UpdateAgentCommand command) {
        throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    @Override
    @Transactional
    public AgentView updatePrompt(CurrentActor actor, Long agentId, String systemPrompt) {
        requireManage(actor);
        validatePrompt(systemPrompt);
        AgentPromptGuard.validate(systemPrompt);
        TenantAgent agent = getDefaultScoped(actor, agentId);
        try {
            agent.updateSystemPrompt(systemPrompt);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        TenantAgent saved = agents.save(agent);
        recordHistory(saved, actor.userId(), AgentPromptChangeType.SAVE);
        audit.record(new AuditEvent("AGENT_PROMPT_UPDATED", "TENANT_AGENT", saved.getId(), AuditResult.SUCCESS,
                "agent-service", Map.of("resourceName", saved.getName())));
        return AgentView.from(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PromptHistoryView> promptHistory(CurrentActor actor, Long agentId) {
        TenantAgent agent = resolveManageableAgent(actor, agentId);
        if (promptHistory == null) {
            return List.of();
        }
        return promptHistory.findTop20ByAgentIdOrderByRevisionDesc(agent.getId()).stream()
                .map(PromptHistoryView::from)
                .toList();
    }

    @Override
    @Transactional
    public AgentView rollbackPrompt(CurrentActor actor, Long agentId, int revision) {
        TenantAgent agent = resolveManageableAgent(actor, agentId);
        if (promptHistory == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        TenantAgentPromptHistory target = promptHistory.findByAgentIdAndRevision(agent.getId(), revision)
                .filter(history -> agent.getTenantId() == null
                        || java.util.Objects.equals(history.getTenantId(), agent.getTenantId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        try {
            agent.updateSystemPrompt(target.getSystemPrompt());
        } catch (IllegalArgumentException exception) {
            // 该版本为空（平台应急清空的记录）时不允许回滚，避免把规则恢复成空白
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "该历史版本内容为空，无法回滚");
        }
        TenantAgent saved = agents.save(agent);
        recordHistory(saved, actor.userId(), AgentPromptChangeType.ROLLBACK);
        audit.record(new AuditEvent("AGENT_PROMPT_ROLLED_BACK", "TENANT_AGENT", saved.getId(),
                AuditResult.SUCCESS, "agent-service",
                Map.of("resourceName", saved.getName(), "reason", "revision=" + revision)));
        return AgentView.from(saved);
    }

    @Override
    @Transactional
    public AgentView clearPrompt(CurrentActor actor, Long agentId) {
        if (actor == null || !actor.isPlatformAdmin()) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        TenantAgent agent = agents.findById(agentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        agent.clearSystemPrompt();
        TenantAgent saved = agents.save(agent);
        recordHistory(saved, actor.userId(), AgentPromptChangeType.CLEAR);
        audit.record(new AuditEvent("AGENT_PROMPT_CLEARED", "TENANT_AGENT", saved.getId(),
                AuditResult.SUCCESS, "agent-service", Map.of("resourceName", saved.getName())));
        return AgentView.from(saved);
    }

    /** 追加一条提示词历史，并裁剪到最近 {@value #PROMPT_HISTORY_LIMIT} 版 */
    private void recordHistory(TenantAgent agent, Long changedByUserId, AgentPromptChangeType changeType) {
        if (promptHistory == null || agent.getId() == null || agent.getTenantId() == null) {
            return;
        }
        int next = promptHistory.findMaxRevision(agent.getId()) + 1;
        promptHistory.save(TenantAgentPromptHistory.record(agent.getTenantId(), agent.getId(), next,
                agent.getSystemPrompt(), changedByUserId, changeType));
        int oldestKept = next - PROMPT_HISTORY_LIMIT + 1;
        if (oldestKept > 1) {
            promptHistory.deleteByAgentIdAndRevisionLessThan(agent.getId(), oldestKept);
        }
    }

    /** 门店管理员走原有权限校验；平台管理员允许跨门店查看与回滚 */
    private TenantAgent resolveManageableAgent(CurrentActor actor, Long agentId) {
        if (actor != null && actor.isPlatformAdmin()) {
            return agents.findById(agentId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        }
        requireManage(actor);
        return getScoped(actor, agentId);
    }

    @Override
    @Transactional
    public AgentView disable(CurrentActor actor, Long agentId) {
        throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    @Override
    @Transactional
    public AgentView activate(CurrentActor actor, Long agentId) {
        throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    private TenantAgent getScoped(CurrentActor actor, Long agentId) {
        return agents.findByTenantIdAndId(ActorAccess.tenantId(actor), agentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private TenantAgent getDefaultScoped(CurrentActor actor, Long agentId) {
        Long tenantId = ActorAccess.tenantId(actor);
        TenantAgent defaultAgent = agents.findAllByTenantIdAndStatus(tenantId, AgentStatus.ACTIVE).stream()
                .filter(java.util.Objects::nonNull)
                .min(Comparator.comparing(TenantAgent::getId, Comparator.nullsLast(Long::compareTo)))
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        TenantAgent target = getScoped(actor, agentId);
        if (defaultAgent.getId() != null && !java.util.Objects.equals(defaultAgent.getId(), agentId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (defaultAgent.getId() == null && defaultAgent != target) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return target;
    }

    private static void requireTenant(CurrentActor actor) {
        ActorAccess.requireTenantId(actor);
    }

    private static void requireManage(CurrentActor actor) {
        requireTenant(actor);
        if (actor == null || actor.isPlatformAdmin() || !canManage(actor)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    private static boolean canManage(CurrentActor actor) {
        return ActorAccess.canManageTenant(actor) && hasAgentMenu(actor);
    }

    private static boolean hasAgentMenu(CurrentActor actor) {
        return actor != null && (actor.isPlatformAdmin()
                || actor.hasPermission("menu:ai-config")
                || actor.hasPermission("agent:chat")
                || actor.hasPermission("agent:write"));
    }

    /** 平台管理员跨租户只读白名单 */
    private static boolean canBrowseAllTenants(CurrentActor actor) {
        return false;
    }

    private static void validate(CreateAgentCommand command) {
        if (command == null || command.name() == null || command.name().isBlank()
                || command.systemPrompt() == null || command.systemPrompt().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private static void validate(UpdateAgentCommand command) {
        if (command == null || command.name() == null || command.name().isBlank()
                || command.systemPrompt() == null || command.systemPrompt().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private static void validatePrompt(String systemPrompt) {
        if (systemPrompt == null || systemPrompt.isBlank() || systemPrompt.trim().length() > 4000) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }
}
