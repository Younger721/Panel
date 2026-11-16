package com.panel.shared.text;

import org.junit.jupiter.api.Test;

import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "把锚点扩成整句"的边界条件：这是高亮和批注卡共用的规则，
 * 扩多一点就点亮了上一段，扩少一点就又是半句话，全靠这几个用例钉住。
 */
class SentenceSpanTest {

    private static final IntPredicate ALWAYS = index -> true;

    @Test
    void extendsToTheSentenceEndAndKeepsThePeriod() {
        String text = "第一句话先结束。基于 Kafka 双 Topic 实现异步消费，并结合批量消费、消费重试。下一句开始。";
        int start = text.indexOf("基于 Kafka");
        int end = start + "基于 Kafka 双 Topic 实现异步消费，并结合批量".length();

        int[] span = SentenceSpan.bounds(text, start, end, ALWAYS);

        assertEquals("基于 Kafka 双 Topic 实现异步消费，并结合批量消费、消费重试。",
                text.substring(span[0], span[1]));
    }

    @Test
    void extendsBackToThePreviousSentenceMark() {
        String text = "前一整句话到此为止。标注结果异步回流：基于 Kafka 双 Topic 实现异步消费。";
        int start = text.indexOf("Kafka");
        int end = start + "Kafka 双 Topic".length();

        int[] span = SentenceSpan.bounds(text, start, end, ALWAYS);

        assertEquals("标注结果异步回流：基于 Kafka 双 Topic 实现异步消费。", text.substring(span[0], span[1]));
    }

    @Test
    void stopsAtABulletInsteadOfSwallowingThePreviousItem() {
        String text = "• 中国机器人及人工智能大赛 国家三等奖\n• 中国软件杯大学生软件设计大赛国家三等奖";
        int start = text.indexOf("中国软件杯");
        int end = start + "中国软件杯大学生软件设计大赛".length();

        int[] span = SentenceSpan.bounds(text, start, end, ALWAYS);

        assertEquals("中国软件杯大学生软件设计大赛国家三等奖", text.substring(span[0], span[1]));
    }

    @Test
    void stopsAtALineBreak() {
        String text = "上一段的结尾没有句号\n这一句也不长";
        int start = text.indexOf("这一句");
        int end = start + "这一句".length();

        int[] span = SentenceSpan.bounds(text, start, end, ALWAYS);

        assertEquals("这一句也不长", text.substring(span[0], span[1]));
    }

    @Test
    void honoursTheGeometryGuard() {
        String text = "正文讲到一半没有句号就换了版式品牌标题从这里开始";
        int start = text.indexOf("正文");
        int end = start + 4;
        // 第 10 个字符处是"版式"的开始：调用方说这里跨不过去（正文 → 标题）
        int cut = text.indexOf("品牌");
        IntPredicate joined = index -> index < cut - 1;

        int[] span = SentenceSpan.bounds(text, start, end, joined);

        assertTrue(span[1] <= cut, "不该跨过调用方挡住的位置，实际到 " + span[1]);
        assertFalse(SentenceSpan.endsSentence(text.substring(span[0], span[1])));
    }

    @Test
    void leavesAlreadyCompleteSentencesAlone() {
        String text = "这是一句已经完整的话。后面还有别的。";
        int start = text.indexOf("这是一句");
        int end = start + "这是一句已经完整的话。".length();

        int[] span = SentenceSpan.bounds(text, start, end, ALWAYS);

        assertEquals("这是一句已经完整的话。", text.substring(span[0], span[1]));
    }

    @Test
    void capsHowFarItReaches() {
        String longSentence = "甲".repeat(400);
        int[] span = SentenceSpan.bounds(longSentence, 200, 210, ALWAYS);

        assertTrue(span[0] >= 200 - 80, "左侧最多补 80 个字");
        assertTrue(span[1] <= 210 + 161, "右侧最多补 160 个字，再加句号");
    }

    @Test
    void survivesEmptyInput() {
        int[] span = SentenceSpan.bounds("", 3, 5, ALWAYS);
        assertEquals(3, span[0]);
        assertEquals(5, span[1]);
    }
}
