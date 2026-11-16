package com.panel.ai.harness.plugins;

import com.panel.ai.domain.ContextSegment;
import com.panel.ai.harness.AiHarnessPlugin;
import com.panel.ai.harness.AiHarnessPluginDescriptor;
import com.panel.shared.security.CurrentActor;
import org.springframework.stereotype.Component;

import java.util.List;

/** 基础对话插件：不贡献外部上下文，允许无租户平台管理员使用纯 LLM */
@Component
public class BasicChatHarnessPlugin implements AiHarnessPlugin {

    private static final AiHarnessPluginDescriptor DESCRIPTOR = new AiHarnessPluginDescriptor(
            "basic_chat",
            "基础对话",
            "直接调用大模型，不挂载额外工具",
            "CORE",
            "ai:chat",
            false,
            true);

    @Override
    public AiHarnessPluginDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public boolean supports(CurrentActor actor) {
        if (actor.isPlatformAdmin()) {
            return true;
        }
        return AiHarnessPlugin.super.supports(actor);
    }

    @Override
    public void contribute(CurrentActor actor, String input, List<ContextSegment> context) {
        // 核心插件不追加上下文
    }
}
