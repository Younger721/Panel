package com.panel.interview.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.panel.interview.agent.model.InterviewPlan;
import com.panel.interview.agent.model.JobFit;
import com.panel.interview.application.ResumeAnalysisService.AnalysisView;
import com.panel.interview.application.ResumeAnalysisService.ModuleView;
import com.panel.interview.application.ResumeAnalysisService.PointView;
import com.panel.interview.application.ResumeAnalysisService.Stats;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Planner 不调模型，它的全部价值在于"先问哪一个"：
 * 没 JD 时先问写弱的，有 JD 时先问岗位要的。
 */
class PlannerAgentTest {

    private final PlannerAgent planner = new PlannerAgent();

    @Test
    void picksTheWeakestPointWhenThereIsNoJd() {
        InterviewPlan plan = planner.plan(analysis(), 1);
        assertThat(plan.points()).hasSize(1);
        assertThat(plan.points().get(0).quote()).isEqualTo("调过 JVM 内存");
        assertThat(plan.fit()).isNull();
    }

    @Test
    void prefersThePointThatHitsARequiredSkillFromTheJd() {
        InterviewPlan plan = planner.plan(analysis(), 1, kafkaFit());
        // Kafka 那个点写得并不弱，但岗位要它，就先问它
        assertThat(plan.points()).hasSize(1);
        assertThat(plan.points().get(0).quote()).isEqualTo("用 Kafka 做订单异步解耦");
    }

    @Test
    void fallsBackToWeaknessOrderWhenTheJdMatchesNothing() {
        JobFit fit = new JobFit("不匹配", 20, List.of(), List.of(), List.of("Rust"));
        InterviewPlan plan = planner.plan(analysis(), 1, fit);
        assertThat(plan.points().get(0).quote()).isEqualTo("调过 JVM 内存");
    }

    @Test
    void keepsTheJobFitInsideTheStoredPlan() throws Exception {
        // 存 plan_json 而不是加表：这是"不需要迁移"的前提，值得钉住
        ObjectMapper mapper = new ObjectMapper();
        InterviewPlan plan = new InterviewPlan("strategy", List.of(), "岗位 JD 原文", kafkaFit());

        InterviewPlan back = mapper.readValue(mapper.writeValueAsString(plan), InterviewPlan.class);

        assertThat(back.jd()).isEqualTo("岗位 JD 原文");
        assertThat(back.fit().prioritySkills()).containsExactly("Kafka");
    }

    @Test
    void readsPlansWrittenBeforeTheJdFeature() throws Exception {
        InterviewPlan back = new ObjectMapper()
                .readValue("{\"strategy\":\"s\",\"points\":[]}", InterviewPlan.class);

        assertThat(back.jd()).isNull();
        assertThat(back.fit()).isNull();
    }

    private static JobFit kafkaFit() {
        return new JobFit("后端匹配", 70,
                List.of(new JobFit.Match("Kafka", "用 Kafka 做订单异步解耦")),
                List.of(),
                List.of("Kafka"));
    }

    private static AnalysisView analysis() {
        PointView kafka = new PointView("p1", "消息队列实践", "用 Kafka 做订单异步解耦", null, "STRONG",
                "写了 Kafka 但没说清楚怎么用", List.of(), List.of(), null);
        PointView jvm = new PointView("p2", "JVM 调优", "调过 JVM 内存", null, "WEAK",
                "JVM 参数没写清楚", List.of(), List.of(), null);
        ModuleView module = new ModuleView("PROJECT", "订单系统", "一个订单系统", List.of(),
                List.of(kafka, jvm));
        return new AnalysisView(1L, "model", "", List.of(), List.of(module),
                new Stats(1, 2, 1, 0, 1), Instant.now());
    }
}
