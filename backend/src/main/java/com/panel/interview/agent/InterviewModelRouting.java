package com.panel.interview.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 按 agent 选模型。
 *
 * <p>默认什么都不做：{@code panel.interview.model.cheap} 留空时所有角色都沿用
 * {@code panel.ai.dashscope.chat-model}，也就是和改造前完全一致——路由是一个
 * 可选的成本旋钮，不是默认行为的变化。</p>
 */
@Component
public class InterviewModelRouting {

    private final String cheapModel;

    public InterviewModelRouting(@Value("${panel.interview.model.cheap:}") String cheapModel) {
        this.cheapModel = cheapModel == null ? "" : cheapModel.trim();
    }

    /**
     * @return 该角色应该用的模型；{@code null} 表示沿用网关默认模型
     */
    public String modelFor(InterviewAgentRole role) {
        return role.cheapModelAllowed() && !cheapModel.isBlank() ? cheapModel : null;
    }

    public boolean cheapModelConfigured() {
        return !cheapModel.isBlank();
    }
}
