package com.panel.interview.agent;

import com.panel.ai.application.ChatModelCallResult;
import com.panel.ai.application.DefaultAiUsageService;
import com.panel.shared.security.CurrentActor;
import com.panel.shared.security.CurrentActorProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 把一次面试模型调用记到用量账上。
 *
 * <p>记账是旁路：拿不到 actor（后台线程）就跳过，写库失败也只打日志。
 * 面试本身不能因为"记账失败"而失败——这是这里唯一重要的设计约束。</p>
 */
@Component
public class AgentUsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(AgentUsageRecorder.class);

    private final DefaultAiUsageService usage;
    private final CurrentActorProvider actors;

    public AgentUsageRecorder(DefaultAiUsageService usage, CurrentActorProvider actors) {
        this.usage = usage;
        this.actors = actors;
    }

    public void record(InterviewAgentRole role, ChatModelCallResult result) {
        if (result == null) {
            return;
        }
        CurrentActor actor = actors.current().orElse(null);
        if (actor == null) {
            // 简历解析跑在后台线程、没有请求上下文：宁可少记一条，也不伪造 tenant
            return;
        }
        try {
            usage.record(actor, result, role.feature());
        } catch (RuntimeException ex) {
            log.warn("AI 用量记账失败（不影响面试）：{}", ex.getMessage());
        }
    }
}
