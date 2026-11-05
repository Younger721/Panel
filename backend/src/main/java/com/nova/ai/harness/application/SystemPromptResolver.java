package com.nova.ai.harness.application;

import com.nova.agent.domain.model.AgentStatus;
import com.nova.agent.domain.model.TenantAgent;
import com.nova.agent.domain.repository.TenantAgentRepository;
import com.nova.ai.chat.application.AiPromptSkeleton;
import com.nova.ai.chat.application.AiRoleProfile;
import com.nova.shared.security.ActorAccess;
import com.nova.shared.security.CurrentActor;

import java.util.Comparator;
import java.util.Objects;

/**
 * 系统提示词分层：平台打底 → 角色规则（不可覆盖）→ 门店补充 → 安全红线。
 *
 * <p>平台层来自平台配置，门店层来自该门店启用的 Agent，角色规则写死在代码里。
 * 三层各有唯一负责人，门店只能改自己那一层，红线永远收尾。</p>
 */
public class SystemPromptResolver {

    private final HarnessToolCatalog catalog;
    private final TenantAgentRepository tenantAgents;

    public SystemPromptResolver(HarnessToolCatalog catalog, TenantAgentRepository tenantAgents) {
        this.catalog = catalog;
        this.tenantAgents = tenantAgents;
    }

    /** 拼出本次对话的系统提示词；门店层为空时自动跳过 */
    public String resolve(CurrentActor actor, AiRoleProfile profile) {
        String platformPrompt = catalog.platformConfig().getSystemPrompt();
        String roleRules = profile == null ? null : profile.systemPrompt();
        if (actor.isPlatformAdmin()) {
            return AiPromptSkeleton.compose(platformPrompt, roleRules, null);
        }
        // 顺序固定：平台打底 → 角色规则（不可覆盖）→ 门店补充 → 安全红线
        return AiPromptSkeleton.compose(platformPrompt, roleRules, tenantPrompt(actor));
    }

    /** 门店补充层：取该门店启用中 id 最小的 Agent 的提示词 */
    private String tenantPrompt(CurrentActor actor) {
        Long tenantId = ActorAccess.tenantId(actor);
        if (tenantAgents == null || tenantId == null) {
            return null;
        }
        return tenantAgents.findAllByTenantIdAndStatus(tenantId, AgentStatus.ACTIVE).stream()
                .filter(Objects::nonNull)
                .min(Comparator.comparing(TenantAgent::getId, Comparator.nullsLast(Long::compareTo)))
                .map(TenantAgent::getSystemPrompt)
                .filter(prompt -> prompt != null && !prompt.isBlank())
                .orElse(null);
    }
}
