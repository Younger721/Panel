package com.panel.interview.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.panel.interview.agent.model.TurnEvaluation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 评估 JSON 的向后兼容性：顶层的字段形状不能变，{@code judges} 只能是新增。 */
class EvaluationJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static TurnEvaluation evaluation(int accuracy, int depth, int completeness, int clarity, String feedback) {
        return new TurnEvaluation(
                new TurnEvaluation.Scores(accuracy, depth, completeness, clarity),
                List.of(new TurnEvaluation.Evidence("原文片段", "说明了什么")),
                List.of("扣分点"),
                feedback,
                true);
    }

    @Test
    void keepsLegacyShapeWhenExpressionJudgeIsMissing() throws Exception {
        String json = EvaluationJson.write(mapper, evaluation(80, 60, 70, 50, "技术不错"), null);

        JsonNode node = mapper.readTree(json);
        assertThat(node.path("scores").path("accuracy").asInt()).isEqualTo(80);
        assertThat(node.path("feedback").asText()).isEqualTo("技术不错");
        assertThat(node.path("evidence")).hasSize(1);
        // 表达评委失败时不能凭空造一个评委出来
        assertThat(node.has("judges")).isFalse();
    }

    @Test
    void storesBothJudgesButKeepsTechnicalScoreOnTop() throws Exception {
        String json = EvaluationJson.write(mapper,
                evaluation(80, 60, 70, 50, "技术不错"),
                evaluation(60, 40, 50, 85, "讲得清楚但细节少"));

        JsonNode node = mapper.readTree(json);
        JsonNode judges = node.path("judges");
        assertThat(judges).hasSize(2);
        assertThat(judges.get(0).path("name").asText()).isEqualTo(EvaluationJson.TECHNICAL_JUDGE);
        assertThat(judges.get(1).path("name").asText()).isEqualTo(EvaluationJson.EXPRESSION_JUDGE);
        assertThat(judges.get(1).path("scores").path("clarity").asInt()).isEqualTo(85);

        // 顶层仍是技术评委的分：成绩口径不因为多了个评委而改变
        assertThat(node.path("scores").path("accuracy").asInt()).isEqualTo(80);
        assertThat(node.path("scores").path("clarity").asInt()).isEqualTo(50);
    }
}
