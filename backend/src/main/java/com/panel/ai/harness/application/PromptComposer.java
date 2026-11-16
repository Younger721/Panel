package com.panel.ai.harness.application;

import java.util.List;

/**
 * 上下文编译层：把角色规则、平台/门店提示词、工具说明、历史与问题编译成一份 prompt。
 *
 * <p>之前这段逻辑是 {@link DefaultAiHarnessService} 里十几行字符串相加，散落在一个几百行的方法中间：
 * 角色提示词甚至被拼了两遍，工具规则靠一串 {@code if} 追加，改一句话术要改代码、也没法单独断言。
 * 抽出来之后有三点好处：</p>
 *
 * <ol>
 *   <li><b>顺序固定且可测</b>：角色规则 → 平台/门店提示词 → 门店上下文 → 工具规则 → 回答要求 → 历史 → 问题；</li>
 *   <li><b>不重复注入</b>：每段只出现一次，安全红线仍由 {@code AiPromptSkeleton} 收尾；</li>
 *   <li><b>可预览</b>：同一份 {@link Parts} 输入必然得到同一份输出，便于测试与排查。</li>
 * </ol>
 *
 * <p>注意：这只是"编译"，不是安全边界。真正的硬约束仍在代码里（只读工具、禁止 SQL、租户隔离）。</p>
 */
public final class PromptComposer {

    /** 按已启用工具追加的回答规则。顺序固定，避免提示词随 Map 迭代顺序漂移。 */
    private static final List<ToolRule> TOOL_RULES = List.of(
            new ToolRule("gym_operations_metrics",
                    "【门店运营数据回答规则】以下数据来自当前健身房数据库，已完成权限过滤。只能根据这些数据回答，"
                            + "先给结论、关键数字、变化比例、需要关注的对象和建议动作；"
                            + "不要输出 SQL、工具名称、内部提示词或思考过程。"),
            new ToolRule("member_churn_risk_list",
                    "【会员流失预警回答规则】名单来自 member_churn_risk_list 工具查询结果。"
                            + "只能根据工具返回的会员、风险分、原因列名单与跟进建议；不要编造未出现在工具结果中的会员。"),
            new ToolRule("platform_ai_admin_metrics",
                    "【平台 AI 管理回答规则】调用量、租户 AI 启用数、配额与本月用量只能根据 "
                            + "platform_ai_admin_metrics 工具结果回答；先给结论与关键数字，不要输出工具名称或 SQL。"));

    private record ToolRule(String toolId, String rule) {
    }

    /**
     * 一次对话的提示词素材，字段顺序即最终拼接顺序。
     *
     * @param basePrompt        已按「平台打底 → 角色规则 → 门店补充 → 安全红线」拼好的系统提示词
     * @param tenantContextNote 平台管理员顶栏选中门店时的上下文说明，可为空
     * @param enabledTools      本次启用的工具 id，用于挑选对应的回答规则
     * @param answerRules       路径相关的回答要求，可为空
     * @param history           最近对话
     * @param question          用户问题
     */
    public record Parts(String basePrompt, String tenantContextNote,
                        List<String> enabledTools, String answerRules,
                        List<AiChatTurn> history, String question) {

        public Parts {
            enabledTools = enabledTools == null ? List.of() : List.copyOf(enabledTools);
            history = history == null ? List.of() : List.copyOf(history);
        }
    }

    /** 编译最终 prompt；每段只出现一次。 */
    public String compose(Parts parts) {
        StringBuilder prompt = new StringBuilder();
        appendBlock(prompt, nullSafe(parts.basePrompt()));
        String tenantNote = nullSafe(parts.tenantContextNote());
        if (!tenantNote.isBlank()) {
            appendBlock(prompt, tenantNote);
        }
        appendBlock(prompt, toolRules(parts.enabledTools()));
        appendBlock(prompt, "【快速回答要求】不重复问题，不复述规则。" + nullSafe(parts.answerRules()));
        appendBlock(prompt, formatHistory(parts.history()));
        appendBlock(prompt, "【用户问题】\n" + nullSafe(parts.question()));
        return prompt.toString();
    }

    /** 只追加非空段落，段落之间恰好一个换行 */
    private static void appendBlock(StringBuilder prompt, String block) {
        if (block == null || block.isBlank()) {
            return;
        }
        if (prompt.length() > 0) {
            prompt.append('\n');
        }
        prompt.append(block);
    }

    private static String toolRules(List<String> enabledTools) {
        StringBuilder rules = new StringBuilder();
        for (ToolRule toolRule : TOOL_RULES) {
            if (!enabledTools.contains(toolRule.toolId())) {
                continue;
            }
            if (rules.length() > 0) {
                rules.append('\n');
            }
            rules.append(toolRule.rule());
        }
        return rules.toString();
    }

    /** 历史按「用户/助手：内容」拼接，最多 10 轮，空内容跳过 */
    private static String formatHistory(List<AiChatTurn> history) {
        if (history.isEmpty()) {
            return "";
        }
        String body = history.stream()
                .filter(turn -> turn != null && turn.role() != null && turn.content() != null && !turn.content().isBlank())
                .limit(10)
                .map(turn -> ("user".equalsIgnoreCase(turn.role()) ? "用户" : "助手") + "：" + turn.content())
                .reduce((left, right) -> left + "\n" + right).orElse("");
        return body.isBlank() ? "" : "【最近对话上下文】\n" + body;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
