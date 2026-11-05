package com.nova.ai.harness.application;

import com.nova.ai.chat.application.AiRoleProfile;
import com.nova.ai.harness.AiHarnessPluginDescriptor;
import com.nova.ai.harness.AiHarnessPluginRegistry;
import com.nova.ai.harness.HarnessPluginList;
import com.nova.ai.harness.capability.AiCapabilityRegistry;
import com.nova.ai.harness.capability.CapabilityDescriptor;
import com.nova.ai.harness.capability.CapabilityOrigin;
import com.nova.ai.harness.config.AiHarnessProperties;
import com.nova.ai.harness.domain.PlatformHarnessConfig;
import com.nova.ai.harness.domain.PlatformHarnessConfigRepository;
import com.nova.iam.domain.model.RoleCode;
import com.nova.shared.security.ActorAccess;
import com.nova.shared.security.CurrentActor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 工具清单：一次对话"能带哪些工具"、平台配置"关了哪些工具"、门店看到什么，都从这里算。
 *
 * <p>之前这些逻辑散在 {@code DefaultAiHarnessService} 的七八个私有方法里，与对话主流程、
 * 配置读写混在一起。抽出来后有两个直接好处：对话主流程只剩编排；配置视图与对话用的是
 * 同一套计算，不会再出现"面板显示能用、对话里不能用"。</p>
 */
public class HarnessToolCatalog {

    private final AiHarnessPluginRegistry registry;
    private final AiCapabilityRegistry capabilityRegistry;
    private final PlatformHarnessConfigRepository platformConfigs;
    private final AiHarnessProperties properties;

    public HarnessToolCatalog(AiHarnessPluginRegistry registry,
                              AiCapabilityRegistry capabilityRegistry,
                              PlatformHarnessConfigRepository platformConfigs,
                              AiHarnessProperties properties) {
        this.registry = registry;
        this.capabilityRegistry = capabilityRegistry;
        this.platformConfigs = platformConfigs;
        this.properties = properties;
    }

    /** 平台配置单例；不存在时按默认值建一条并落库（测试环境仓储常返回 null，回落到内存实例） */
    public PlatformHarnessConfig platformConfig() {
        return platformConfigs.findSingleton().orElseGet(() -> {
            PlatformHarnessConfig created = new PlatformHarnessConfig(
                    HarnessPluginList.join(properties.getPlatformDefaultPlugins()),
                    properties.getDefaultSystemPrompt());
            PlatformHarnessConfig saved = platformConfigs.save(created);
            return saved == null ? created : saved;
        });
    }

    /**
     * 一次对话最终生效的工具清单。
     *
     * <p>规则：工具配置只属于平台。门店传进来的 {@code sessionPlugins} 不参与计算，
     * 门店侧能力完全由平台已启用清单 ∩ 角色模板决定；平台管理员可在会话内收窄。</p>
     */
    public List<String> enabledFor(CurrentActor actor, List<String> sessionPlugins, AiRoleProfile profile) {
        List<String> platformPlugins = HarnessPluginList.parse(platformConfig().getEnabledPlugins());
        List<String> configured = actor.isPlatformAdmin()
                ? platformPlugins
                : HarnessPluginList.intersect(platformPlugins, profile.enabledPlugins());
        List<String> requested = actor.isPlatformAdmin() ? sessionPlugins : null;
        List<String> base = requested != null && !requested.isEmpty() ? requested : configured;
        if (configured.contains("knowledge_search")) {
            base = concat(base, "knowledge_search");
        } else {
            base = base.stream().filter(id -> !"knowledge_search".equals(id)).toList();
        }
        // 平台已启用的远程 MCP 工具随角色一并下发：登记在配置里即视为平台授权，
        // 门店不参与开关；权限与可见范围仍由能力自身的 supports() 兜底。
        return HarnessPluginList.intersect(merge(base, remoteIds(actor)), allowedIds(actor));
    }

    /** 门店侧配置视图：平台已启用 ∩ 角色模板，再并上远程工具 */
    public List<String> tenantViewFor(CurrentActor actor, AiRoleProfile profile) {
        return HarnessPluginList.intersect(
                merge(HarnessPluginList.intersect(
                                HarnessPluginList.parse(platformConfig().getEnabledPlugins()),
                                profile.enabledPlugins()),
                        remoteIds(actor)),
                allowedIds(actor));
    }

    /** 当前身份可用的远程 MCP 工具 id（平台登记后自动生效，不吃"空=全量"语义） */
    public List<String> remoteIds(CurrentActor actor) {
        if (capabilityRegistry == null) {
            return List.of();
        }
        return capabilityRegistry.listByOrigin(actor, CapabilityOrigin.MCP_REMOTE).stream()
                .map(capability -> capability.descriptor().id())
                .toList();
    }

    /** 当前身份可用的全部能力 id，本地 + 远程 */
    public List<String> allowedIds(CurrentActor actor) {
        if (capabilityRegistry == null) {
            return registry.listFor(actor).stream().map(AiHarnessPluginDescriptor::id).toList();
        }
        List<String> local = capabilityRegistry.listFor(actor, List.of()).stream()
                .map(capability -> capability.descriptor().id()).toList();
        return merge(local, remoteIds(actor));
    }

    /** 仅店长补齐门店运营工具；平台管理员保持平台 AI 问答 */
    public List<String> expandStoreOps(CurrentActor actor, List<String> enabled) {
        if (actor == null || actor.isPlatformAdmin()
                || !actor.roles().contains(RoleCode.GYM_ADMIN)
                || ActorAccess.tenantId(actor) == null) {
            return enabled;
        }
        LinkedHashSet<String> merged = new LinkedHashSet<>(enabled);
        merged.add("gym_operations_metrics");
        merged.add("member_churn_risk_list");
        return new ArrayList<>(merged);
    }

    /** 平台管理员保存配置时校验：只接受有权使用的 id，空清单直接拒绝 */
    public List<String> sanitize(CurrentActor actor, List<String> requested) {
        List<String> allowed = allowedIds(actor);
        List<String> picked = requested == null || requested.isEmpty()
                ? List.of("basic_chat")
                : requested;
        List<String> result = HarnessPluginList.intersect(picked, allowed);
        if (result.isEmpty()) {
            throw new com.nova.shared.error.BusinessException(
                    com.nova.shared.error.ErrorCode.VALIDATION_FAILED);
        }
        return result;
    }

    /** 当前身份可见的能力描述（含远程工具），供配置视图展示 */
    public List<CapabilityDescriptor> visible(CurrentActor actor) {
        if (capabilityRegistry == null) {
            return List.of();
        }
        List<CapabilityDescriptor> result = new ArrayList<>();
        capabilityRegistry.listFor(actor, List.of())
                .forEach(capability -> result.add(capability.descriptor()));
        capabilityRegistry.listByOrigin(actor, CapabilityOrigin.MCP_REMOTE)
                .forEach(capability -> result.add(capability.descriptor()));
        return result;
    }

    private static List<String> concat(List<String> values, String value) {
        List<String> result = new ArrayList<>(values);
        result.add(value);
        return result;
    }

    /** 合并两组能力 id，保持前者顺序、后者去重追加 */
    private static List<String> merge(List<String> first, List<String> second) {
        List<String> result = new ArrayList<>(first);
        for (String id : second) {
            if (!result.contains(id)) {
                result.add(id);
            }
        }
        return result;
    }
}
