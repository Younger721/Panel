package com.panel.interview.agent;

import com.panel.ai.application.ChatModelCallResult;
import com.panel.ai.application.ChatModelGateway;
import com.panel.ai.domain.ContextSegment;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * 面试 agent 的统一模型入口：一次调用 = 选模型 + 打点。
 *
 * <p>agent 不再直接依赖 {@link ChatModelGateway}，是为了让"这个 agent 该用哪个模型、
 * 账记在哪个 feature 上"只有一处答案。加新 agent 时照抄一行即可，
 * 不会出现某个环节悄悄绕过路由或漏记用量。</p>
 */
@Component
public class InterviewAgentCall {

    private final ChatModelGateway gateway;
    private final InterviewModelRouting routing;
    private final AgentUsageRecorder usage;

    public InterviewAgentCall(ChatModelGateway gateway, InterviewModelRouting routing, AgentUsageRecorder usage) {
        this.gateway = gateway;
        this.routing = routing;
        this.usage = usage;
    }

    public ChatModelCallResult structuredJson(InterviewAgentRole role, String prompt, List<ContextSegment> context) {
        ChatModelCallResult result = gateway.completeStructuredJson(prompt, context, routing.modelFor(role));
        usage.record(role, result);
        return result;
    }

    /**
     * 流式纯文本：给"报告总评先出来"用。
     *
     * <p>结构化 JSON 必须等完整才能解析，天然没法边生成边展示；把总评从 JSON 里
     * 单独拆出来走流式，用户几秒内就能看到结论，而不是盯着转圈看半分钟。</p>
     *
     * <p>走网关默认模型——流式接口没有按 role 选模型的重载，而总评这种短文本对模型不挑。
     * 用量照样记账，不会漏。</p>
     */
    public ChatModelCallResult streamText(InterviewAgentRole role, String prompt, List<ContextSegment> context,
                                          Consumer<String> onToken) {
        ChatModelCallResult result = gateway.streamWithUsage(prompt, context, onToken);
        usage.record(role, result);
        return result;
    }
}
