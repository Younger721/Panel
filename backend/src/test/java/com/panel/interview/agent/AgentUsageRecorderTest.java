package com.panel.interview.agent;

import com.panel.ai.application.ChatModelCallResult;
import com.panel.ai.application.DefaultAiUsageService;
import com.panel.shared.security.CurrentActor;
import com.panel.shared.security.CurrentActorProvider;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;

/**
 * 记账是旁路：拿不到 actor 就跳过，写库失败也不能把面试带崩。
 */
class AgentUsageRecorderTest {

    private final DefaultAiUsageService usage = Mockito.mock(DefaultAiUsageService.class);
    private final CurrentActorProvider actors = Mockito.mock(CurrentActorProvider.class);

    @Test
    void skipsSilentlyWhenThereIsNoRequestActor() {
        Mockito.when(actors.current()).thenReturn(Optional.empty());

        new AgentUsageRecorder(usage, actors).record(InterviewAgentRole.REVIEW, result());

        // 后台线程没有请求上下文：宁可少记，也不伪造租户
        Mockito.verifyNoInteractions(usage);
    }

    @Test
    void recordsWithTheRoleFeature() {
        CurrentActor actor = Mockito.mock(CurrentActor.class);
        Mockito.when(actors.current()).thenReturn(Optional.of(actor));
        ChatModelCallResult result = result();

        new AgentUsageRecorder(usage, actors).record(InterviewAgentRole.TECHNICAL_JUDGE, result);

        Mockito.verify(usage).record(actor, result, "interview.judge");
    }

    @Test
    void neverThrowsWhenAccountingFails() {
        CurrentActor actor = Mockito.mock(CurrentActor.class);
        Mockito.when(actors.current()).thenReturn(Optional.of(actor));
        Mockito.when(usage.record(any(), any(), any())).thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> new AgentUsageRecorder(usage, actors)
                .record(InterviewAgentRole.REPORT, result()))
                .doesNotThrowAnyException();
    }

    private static ChatModelCallResult result() {
        return ChatModelCallResult.unavailable("{}", 1, "OK");
    }
}
