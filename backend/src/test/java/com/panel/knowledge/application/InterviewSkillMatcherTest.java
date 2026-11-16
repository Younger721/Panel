package com.panel.knowledge.application;

import com.panel.knowledge.domain.model.KbCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 面试 skill（自由文本）→ 知识目录节点的匹配规则。
 *
 * <p>刻意不允许模糊匹配（不调模型、不算编辑距离）：目录是产品结构，错挂比不挂更糟——
 * 答错时给知识点会指到错的地方，学员会以为系统在胡说。</p>
 */
class InterviewSkillMatcherTest {

    private static KbCategory root(String name) {
        KbCategory node = KbCategory.create(1L, KbCategory.ROOT_PARENT_ID, name, null, 0);
        node.applyPath("/1/", name.toLowerCase(), 1);
        return node;
    }

    private static KbCategory child(String name, String pathKey) {
        KbCategory node = KbCategory.create(1L, 1L, name, null, 0);
        node.applyPath("/1/2/", pathKey, 2);
        return node;
    }

    private static String nameOf(KbCategory node) {
        return node == null ? null : node.name();
    }

    @Test
    @DisplayName("名字完全一致（忽略空白/全半角/大小写）")
    void matchesExactName() {
        List<KbCategory> nodes = List.of(root("自我介绍"), root("项目经历"));
        assertEquals("自我介绍", nameOf(InterviewSkillMatcher.match("自我介绍", nodes)));
        assertEquals("项目经历", nameOf(InterviewSkillMatcher.match(" 项目经历 ", nodes)));
        assertEquals("自我介绍", nameOf(InterviewSkillMatcher.match("（自我介绍）", nodes)));
    }

    @Test
    @DisplayName("skill 里带着修饰语，靠包含关系命中，并取最长的那个节点名")
    void matchesByContainmentPreferringLongest() {
        List<KbCategory> nodes = List.of(root("并发"), child("并发编程", "java/并发编程"), root("MySQL"));
        assertEquals("并发编程", nameOf(InterviewSkillMatcher.match("Java 并发编程的锁优化", nodes)));
        assertEquals("并发", nameOf(InterviewSkillMatcher.match("并发基础", nodes)));
    }

    @Test
    @DisplayName("匹配不上就返回 null，不乱挂")
    void returnsNullWhenNothingMatches() {
        List<KbCategory> nodes = List.of(root("自我介绍"));
        assertNull(InterviewSkillMatcher.match("技术栈堆叠无深度标注", nodes));
        assertNull(InterviewSkillMatcher.match("", nodes));
        assertNull(InterviewSkillMatcher.match("自我介绍", List.of()));
    }

    @Test
    @DisplayName("空输入不抛异常，归一化对 null 安全")
    void isNullSafe() {
        assertNull(InterviewSkillMatcher.match(null, List.of(root("自我介绍"))));
        assertEquals("", InterviewSkillMatcher.normalize(null));
        assertEquals("mysql索引", InterviewSkillMatcher.normalize(" MySQL 索引 "));
    }

    @Test
    @DisplayName("面试得分折算观测：>=70 对，>=40 半对，其余错")
    void scoreToObservation() {
        assertEquals("CORRECT", KbInterviewBackfillService.observationOf(85));
        assertEquals("CORRECT", KbInterviewBackfillService.observationOf(70));
        assertEquals("UNSURE", KbInterviewBackfillService.observationOf(69));
        assertEquals("UNSURE", KbInterviewBackfillService.observationOf(40));
        assertEquals("WRONG", KbInterviewBackfillService.observationOf(39));
        assertEquals("WRONG", KbInterviewBackfillService.observationOf(0));
        assertEquals("UNSURE", KbInterviewBackfillService.observationOf(null));
    }
}
