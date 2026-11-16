package com.panel.interview.agent;

import com.panel.ai.application.ChatModelCallResult;
import com.panel.ai.application.ChatModelGateway;
import com.panel.ai.domain.ContextSegment;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * 模型路由与记账：默认行为必须与改造前一致（不传模型），配了便宜模型也只影响整理型任务。
 */
class InterviewAgentCallTest {

    @Test
    void passesNoModelOverrideWhenCheapModelIsNotConfigured() {
        CapturingGateway gateway = new CapturingGateway();
        InterviewAgentCall call = new InterviewAgentCall(gateway, new InterviewModelRouting(""),
                Mockito.mock(AgentUsageRecorder.class));

        for (InterviewAgentRole role : InterviewAgentRole.values()) {
            call.structuredJson(role, "prompt", List.of());
        }

        // 全部走网关默认模型：改造前后调用完全等价
        assertThat(gateway.models).hasSize(InterviewAgentRole.values().length);
        assertThat(gateway.models).allMatch(java.util.Objects::isNull);
    }

    @Test
    void routesOnlyTidyUpWorkToTheCheapModel() {
        CapturingGateway gateway = new CapturingGateway();
        InterviewAgentRole[] roles = InterviewAgentRole.values();
        InterviewAgentCall call = new InterviewAgentCall(gateway, new InterviewModelRouting("qwen-cheap"),
                Mockito.mock(AgentUsageRecorder.class));

        for (InterviewAgentRole role : roles) {
            call.structuredJson(role, "prompt", List.of());
        }

        for (int i = 0; i < roles.length; i++) {
            if (roles[i].cheapModelAllowed()) {
                assertThat(gateway.models.get(i)).as(roles[i].name()).isEqualTo("qwen-cheap");
            } else {
                // 出题和评分不允许降级
                assertThat(gateway.models.get(i)).as(roles[i].name()).isNull();
            }
        }
    }

    @Test
    void recordsUsageOncePerCallWithTheRoleFeature() {
        CapturingGateway gateway = new CapturingGateway();
        AgentUsageRecorder recorder = Mockito.mock(AgentUsageRecorder.class);
        InterviewAgentCall call = new InterviewAgentCall(gateway, new InterviewModelRouting(""), recorder);

        call.structuredJson(InterviewAgentRole.REVIEW, "prompt", List.of());

        verify(recorder).record(eq(InterviewAgentRole.REVIEW), any(ChatModelCallResult.class));
        assertThat(InterviewAgentRole.REVIEW.feature()).isEqualTo("interview.review");
    }

    /** 记录调用方传下来的模型名：null 与具体模型必须能区分。 */
    private static final class CapturingGateway implements ChatModelGateway {

        private final List<String> models = new ArrayList<>();

        @Override
        public String complete(String prompt, List<ContextSegment> context) {
            throw new UnsupportedOperationException("面试只走结构化 JSON");
        }

        @Override
        public ChatModelCallResult completeStructuredJson(String prompt, List<ContextSegment> context,
                                                          String model) {
            models.add(model);
            return ChatModelCallResult.unavailable("{}", 1, "OK");
        }
    }
}
