package com.panel.interview.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.panel.interview.agent.model.TurnEvaluation;

/**
 * 一轮评估的 JSON 形态：技术评委的完整评估，外加（可选）评委团。
 *
 * <p>约定：顶层字段与老版本完全一致（{@code scores / evidence / deductions / feedback / answered}），
 * 前端可以照旧读；{@code judges} 是纯新增字段，老数据没有它，前端按可选处理。
 * 分数仍然以技术评委为准——{@code judges} 只用来展示两位评委的分歧。</p>
 */
final class EvaluationJson {

    static final String TECHNICAL_JUDGE = "技术评委";
    static final String EXPRESSION_JUDGE = "表达评委";

    private EvaluationJson() {
    }

    static String write(ObjectMapper mapper, TurnEvaluation technical, TurnEvaluation expression) {
        ObjectNode node = mapper.valueToTree(technical);
        if (expression != null) {
            ArrayNode judges = node.putArray("judges");
            judges.add(judge(mapper, TECHNICAL_JUDGE, technical));
            judges.add(judge(mapper, EXPRESSION_JUDGE, expression));
        }
        return node.toString();
    }

    private static ObjectNode judge(ObjectMapper mapper, String name, TurnEvaluation evaluation) {
        ObjectNode node = mapper.createObjectNode();
        node.put("name", name);
        node.set("scores", mapper.valueToTree(evaluation.scores()));
        node.put("feedback", evaluation.feedback());
        node.set("evidence", mapper.valueToTree(evaluation.evidence()));
        return node;
    }
}
