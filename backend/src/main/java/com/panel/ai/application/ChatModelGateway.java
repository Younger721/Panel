package com.panel.ai.application;

import com.panel.ai.domain.ContextSegment;

import java.util.List;
import java.util.function.Consumer;

public interface ChatModelGateway {
    String complete(String prompt, List<ContextSegment> context);

    default ChatModelCallResult completeWithUsage(String prompt, List<ContextSegment> context) {
        long started = System.currentTimeMillis();
        try {
            return ChatModelCallResult.unavailable(complete(prompt, context),
                    Math.max(0, System.currentTimeMillis() - started), "OK");
        } catch (RuntimeException exception) {
            return ChatModelCallResult.unavailable(null,
                    Math.max(0, System.currentTimeMillis() - started), "ERROR");
        }
    }

    /**
     * 结构化 JSON 任务（如训练计划草案）：需要更长输出，默认实现退化为普通 complete。
     */
    default ChatModelCallResult completeStructuredJson(String prompt, List<ContextSegment> context) {
        return completeWithUsage(prompt, context);
    }

    /**
     * 指定模型的结构化 JSON 调用（同一个网关服务多个 agent，按步骤选模型）。
     *
     * <p>默认实现忽略模型名：没有多模型能力的网关保持原有行为，调用方不用改。</p>
     *
     * @param model 为空表示沿用网关默认模型
     */
    default ChatModelCallResult completeStructuredJson(String prompt, List<ContextSegment> context, String model) {
        return completeStructuredJson(prompt, context);
    }

    /** Whether this gateway can send native OpenAI-compatible tool definitions. */
    default boolean supportsToolCalling() {
        return false;
    }

    /** First model pass: direct answer or structured tool calls. */
    default ChatModelResponse completeWithTools(String prompt, List<ContextSegment> context,
                                                List<ChatToolDefinition> tools) {
        return ChatModelResponse.direct(complete(prompt, context));
    }

    default String stream(String prompt, List<ContextSegment> context, Consumer<String> onToken) {
        String answer = complete(prompt, context);
        if (answer != null && !answer.isEmpty()) {
            for (int i = 0; i < answer.length(); i += 12) {
                onToken.accept(answer.substring(i, Math.min(answer.length(), i + 12)));
            }
        }
        return answer;
    }

    /**
     * 自定义 system prompt 的流式回答（知识库问答用）。
     *
     * <p>默认实现退回 {@link #stream}：没有专用 system prompt 的网关仍然能跑，
     * 只是提示词骨架由调用方拼进用户消息。</p>
     */
    default String streamWithSystem(String systemPrompt, String prompt, List<ContextSegment> context,
                                    Consumer<String> onToken) {
        return stream(prompt, context, onToken);
    }

    default ChatModelCallResult streamWithUsage(String prompt, List<ContextSegment> context,
                                                Consumer<String> onToken) {
        long started = System.currentTimeMillis();
        try {
            return ChatModelCallResult.unavailable(stream(prompt, context, onToken),
                    Math.max(0, System.currentTimeMillis() - started), "OK");
        } catch (RuntimeException exception) {
            return ChatModelCallResult.unavailable(null,
                    Math.max(0, System.currentTimeMillis() - started), "ERROR");
        }
    }
}
