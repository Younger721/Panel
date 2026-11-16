package com.panel.ai.harness;

import com.panel.ai.domain.ContextSegment;
import com.panel.shared.security.CurrentActor;
import com.panel.shared.security.ActorAccess;

import java.util.List;
import java.util.function.Consumer;

/** 可插拔 AI 能力：向 Harness 会话贡献上下文或副作用 */
public interface AiHarnessPlugin {

    AiHarnessPluginDescriptor descriptor();

    /** 是否对当前 Actor 可用（租户/权限） */
    default boolean supports(CurrentActor actor) {
        AiHarnessPluginDescriptor d = descriptor();
        if (d.requiresTenant() && ActorAccess.tenantId(actor) == null) {
            return false;
        }
        if (d.permission() == null || d.permission().isBlank()) {
            return true;
        }
        return actor.hasPermission(d.permission());
    }

    /** 为本次对话贡献 RAG/工具上下文 */
    void contribute(CurrentActor actor, String input, List<ContextSegment> context);

    /**
     * 入参 JSON Schema（工具化后给模型看）。返回 null 表示"无参数"。
     * 统一到能力协议后，任何工具都要能描述自己的参数，避免模型只能盲调。
     */
    default String inputSchema() {
        return null;
    }

    /** 可选的系统执行状态回调，默认保持旧插件行为。 */
    default void contribute(CurrentActor actor, String input, List<ContextSegment> context,
                            Consumer<AiProgressEvent> progress) {
        contribute(actor, input, context);
    }
}
