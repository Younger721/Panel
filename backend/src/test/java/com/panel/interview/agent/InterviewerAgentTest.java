package com.panel.interview.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.panel.ai.application.ChatModelCallResult;
import com.panel.ai.application.ChatModelGateway;
import com.panel.ai.domain.ContextSegment;
import com.panel.interview.agent.model.InterviewPlan;
import com.panel.interview.agent.model.QuestionDraft;
import com.panel.interview.domain.model.InterviewTurn;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 出题的重试与降级：模型偶发违规不应该让整场面试断掉。
 *
 * <p>只用纯 stub 网关，不打真模型——这里验的是编排行为，不是模型能力。</p>
 */
class InterviewerAgentTest {

    private static final InterviewPlan PLAN = new InterviewPlan("test", List.of(
            new InterviewPlan.Point("P1", "Kafka", "ADVANCED", 1, List.of(),
                    "自己写了一个消费端", "验证顺序性", "WEAK"),
            new InterviewPlan.Point("P2", "Redis", "BASIC", 1, List.of(),
                    "用 Redis 做了缓存", "验证一致性", "MEDIUM")));

    private final ObjectMapper objectMapper = new ObjectMapper();

    private InterviewAgentCall agentCall(ChatModelGateway gateway) {
        return new InterviewAgentCall(gateway, new InterviewModelRouting(""),
                Mockito.mock(AgentUsageRecorder.class));
    }

    @Test
    void retriesWithCorrectionWhenModelRepeatsACoveredPoint() {
        AtomicInteger calls = new AtomicInteger();
        List<String> contexts = new ArrayList<>();
        InterviewerAgent agent = new InterviewerAgent(
                agentCall(gateway(calls, contexts, attempt -> attempt == 1
                        ? draft("P1", "再说说 Kafka 那段")
                        : draft("P2", "Redis 缓存怎么保证和数据库一致"))),
                objectMapper);

        QuestionDraft result = agent.next(PLAN, List.of(answered("P1", "之前问过 Kafka")));

        assertThat(result.pointId()).isEqualTo("P2");
        assertThat(result.question()).isEqualTo("Redis 缓存怎么保证和数据库一致");
        assertThat(calls).hasValue(2);
        // 第二次调用必须带上"上次错在哪"，否则重试就是盲猜
        assertThat(contexts.get(1)).contains("上一次输出被拒绝").contains("已经问过");
    }

    @Test
    void fallsBackToRuleBasedQuestionWhenModelKeepsPickingUnknownPoints() {
        AtomicInteger calls = new AtomicInteger();
        InterviewerAgent agent = new InterviewerAgent(
                agentCall(gateway(calls, new ArrayList<>(), attempt -> draft("P9", "随便问问"))),
                objectMapper);

        QuestionDraft result = agent.next(PLAN, List.of());

        assertThat(calls).hasValue(2);
        // 兜底题必须落在真实考察点上，否则后面的评分没有依据
        assertThat(result.pointId()).isEqualTo("P1");
        assertThat(result.question()).isNotBlank();
        assertThat(result.question()).contains("自己写了一个消费端");
    }

    @Test
    void fallsBackToFixedFollowUpWhenModelKeepsReturningEmptyQuestions() {
        AtomicInteger calls = new AtomicInteger();
        InterviewerAgent agent = new InterviewerAgent(
                agentCall(gateway(calls, new ArrayList<>(), attempt -> "{\"question\":\"\",\"intent\":\"x\"}")),
                objectMapper);

        QuestionDraft result = agent.followUp(PLAN.point("P2"),
                List.of(answered("P2", "你们缓存怎么做的？")), "就那样吧");

        assertThat(calls).hasValue(2);
        assertThat(result.type()).isEqualTo("FOLLOW_UP");
        assertThat(result.pointId()).isEqualTo("P2");
        assertThat(result.question()).contains("具体做法");
    }

    private static String draft(String pointId, String question) {
        return """
                {"question":"%s","type":"PROJECT","difficulty":"ADVANCED",
                 "pointId":"%s","intent":"x","followUps":[]}
                """.formatted(question, pointId);
    }

    private static InterviewTurn answered(String pointId, String question) {
        InterviewTurn turn = InterviewTurn.ask(1L, 1, pointId, "Kafka", "ADVANCED", question,
                "intent", "[]", "MAIN", null, 0);
        turn.answer("上一次的回答");
        return turn;
    }

    /**
     * @param responder 第 n 次调用（从 1 开始）返回的内容
     */
    private static ChatModelGateway gateway(AtomicInteger calls, List<String> contexts,
                                            Function<Integer, String> responder) {
        return new ChatModelGateway() {
            @Override
            public String complete(String prompt, List<ContextSegment> context) {
                throw new UnsupportedOperationException("出题只走结构化 JSON");
            }

            @Override
            public ChatModelCallResult completeStructuredJson(String prompt, List<ContextSegment> context) {
                int attempt = calls.incrementAndGet();
                contexts.add(context.stream().map(ContextSegment::text).collect(Collectors.joining("\n")));
                return ChatModelCallResult.unavailable(responder.apply(attempt), 1, "OK");
            }
        };
    }
}
