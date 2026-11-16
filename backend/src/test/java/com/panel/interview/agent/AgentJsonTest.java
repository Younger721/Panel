package com.panel.interview.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Agent 输出解析的健壮性。
 *
 * <p>这些用例来自真实故障：一份 3 模块的简历，模型在第 3 个模块被 max tokens 截断，
 * JSON 停在半个对象上，整份简历直接 FAILED —— 而前两个模块的批注其实已经可用。</p>
 */
class AgentJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesPlainObject() {
        JsonNode node = AgentJson.parse(mapper, "{\"points\":[{\"title\":\"a\"}]}", JsonNode.class, "T");
        assertThat(node.path("points")).hasSize(1);
    }

    @Test
    void parsesFencedJson() {
        String raw = "```json\n{\"points\":[{\"title\":\"a\"},{\"title\":\"b\"}]}\n```";
        JsonNode node = AgentJson.parse(mapper, raw, JsonNode.class, "T");
        assertThat(node.path("points")).hasSize(2);
    }

    /** 截断在数组中间：保留已完整的元素，丢掉半截的那个。 */
    @Test
    void salvagesTruncatedArray() {
        String raw = "{\"points\":[{\"title\":\"a\"},{\"title\":\"b\"},{\"title\":\"c\",\"comment\":\"说";
        JsonNode node = AgentJson.parse(mapper, raw, JsonNode.class, "T");
        assertThat(node.path("points")).hasSize(2);
        assertThat(node.path("points").get(1).path("title").asText()).isEqualTo("b");
    }

    /** 截断在字符串中间、还带转义：不能把半个字符串当成合法值。 */
    @Test
    void doesNotBreakOnEscapedQuotes() {
        String raw = "{\"points\":[{\"title\":\"a\\\"b\"},{\"title\":\"c";
        JsonNode node = AgentJson.parse(mapper, raw, JsonNode.class, "T");
        assertThat(node.path("points")).hasSize(1);
        assertThat(node.path("points").get(0).path("title").asText()).isEqualTo("a\"b");
    }

    @Test
    void rejectsContentWithoutAnyObject() {
        assertThatThrownBy(() -> AgentJson.parse(mapper, "抱歉，我无法完成", JsonNode.class, "T"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no JSON object");
    }

    @Test
    void rejectsEmptyContent() {
        assertThatThrownBy(() -> AgentJson.parse(mapper, "   ", JsonNode.class, "T"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty content");
    }

    /**
     * 字符串里的裸换行。
     *
     * <p>回归防线：模型写长批注时会把换行直接打进 JSON 字符串，Jackson 默认拒绝未转义的
     * 控制字符，整个模块的批注就全丢了。实测一份 PDF 简历，两个最长的模块连续三次都栽在
     * 这里，而日志摘要里看到的明明是工整的 JSON——裸换行藏在 300 字之外。</p>
     */
    @Test
    void parsesUnescapedNewlineInsideString() {
        String raw = "{\"points\":[{\"title\":\"换行\",\"comment\":\"第一点\n第二点\r\n第三点\t制表\"}]}";

        JsonNode node = AgentJson.parse(mapper, raw, JsonNode.class, "T");

        assertThat(node.path("points")).hasSize(1);
        assertThat(node.path("points").get(0).path("comment").asText())
                .isEqualTo("第一点\n第二点\r\n第三点\t制表");
    }

    /** 合法的 JSON 不能被这层清洗改坏：转义序列、引号都要原样保留。 */
    @Test
    void leavesValidJsonUntouched() {
        String raw = "{\"text\":\"已有\\n转义和\\\"引号\\\"，还有反斜杠\\\\\"}";

        JsonNode node = AgentJson.parse(mapper, raw, JsonNode.class, "T");

        assertThat(node.path("text").asText()).isEqualTo("已有\n转义和\"引号\"，还有反斜杠\\");
    }

    /** 裸换行 + 截断同时出现：先清洗再修复，两段逻辑不能互相破坏。 */
    @Test
    void repairsTruncatedJsonWithUnescapedNewline() {
        String raw = "{\"points\":[{\"title\":\"a\",\"comment\":\"第一行\n第二行\"},{\"title\":\"b\",\"comment\":\"还没说";

        JsonNode node = AgentJson.parse(mapper, raw, JsonNode.class, "T");

        assertThat(node.path("points")).hasSize(1);
        assertThat(node.path("points").get(0).path("comment").asText()).isEqualTo("第一行\n第二行");
    }
}
