package com.panel.knowledge.application;

import com.panel.knowledge.domain.model.KbChunk;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文档类型闸门：题库库里混着的讲义、README、口播稿不该被切成"题"。
 *
 * <p>来由（真实数据）：题库 KB 27 份文档里，{@code README.md} 抽出 6 道题
 * （题干是「面试题目」「简短回答」「详细解析」，答案全是 NULL），
 * {@code 黄洋_Java后端面试准备.md} 抽出 31 道（「1 一分钟版」「3 技术深挖」
 * 「1 所有数字都要能交代怎么测的」），这些"题"再被归类器挂进
 * 「面试 / 通用表达方法」，就成了用户看到的"知识点被放进面试题里"。</p>
 *
 * <p>判定必须可解释：每条加减分都进 {@code evidence}，日志里能直接回答
 * "这篇为什么没抽题"。</p>
 */
class KbDocumentKindGateTest {

    private final KbDocumentKindGate gate = KbDocumentKindGate.rulesOnly();

    private static KbQuestionDraft draft(String stem, String answer) {
        return new KbQuestionDraft(null, stem, answer, null, null, null, null, List.of());
    }

    private static String filler(int length) {
        return "数组容量扩大为两倍，旧节点按低位重新分配，迁移开销与元素数量成正比。".repeat(Math.max(1, length / 30));
    }

    private static KbDocumentKindGate.Verdict judge(String fileName, String text, List<KbQuestionDraft> drafts) {
        return KbDocumentKindGate.rulesOnly().judge(fileName, text, drafts);
    }

    @Test
    void README模板字段不算题库() {
        String text = """
                # 我的资料说明
                每篇笔记按下面这些段落组织。
                ## 面试题目
                ## 简短回答
                ## 详细解析
                ## 常见追问
                ## 回答要点
                ## 关联知识
                """;
        List<KbQuestionDraft> drafts = List.of(
                draft("面试题目", null), draft("简短回答", null), draft("详细解析", null),
                draft("常见追问", null), draft("回答要点", null), draft("关联知识", null));
        KbDocumentKindGate.Verdict verdict = judge("README.md", text, drafts);
        assertThat(verdict.questionBank()).isFalse();
        assertThat(verdict.summary()).contains("模板字段");
    }

    @Test
    void 面试口播稿不算题库() {
        String text = """
                面试开场我通常这样讲。
                1 一分钟版（约 240 字，适合群面 / 初筛）
                2 两分钟版（约 500 字，适合技术面开场）
                3 技术深挖
                4 需要继续加固的方向
                面试官常追问的点我都准备了。
                """;
        List<KbQuestionDraft> drafts = List.of(
                draft("1 一分钟版（约 240 字，适合群面 / 初筛）", filler(400)),
                draft("2 两分钟版（约 500 字，适合技术面开场）", filler(700)),
                draft("3 技术深挖", filler(300)),
                draft("1 所有数字都要能交代\"怎么测的\"", filler(60)),
                draft("2 主动承认边界", filler(80)),
                draft("4 需要继续加固的方向", filler(80)));
        KbDocumentKindGate.Verdict verdict = judge("黄洋_Java后端面试准备.md", text, drafts);
        assertThat(verdict.questionBank()).isFalse();
        assertThat(verdict.summary()).contains("伪题");
    }

    @Test
    void 专项总结讲义不算题库() {
        String text = """
                ## 一、整体结构
                HashMap 可以理解成"横向数组 + 纵向桶结构"。
                ## 二、扩容解决横向问题
                ### 1. 为什么需要扩容？
                元素越来越多时，冲突概率上升，链表变长。
                ### 2. JDK 8 如何迁移节点？
                扩容后旧桶中的节点只有两种去向。
                ## 三、树化解决纵向问题
                ### 1. 为什么扩容有时解决不了冲突？
                如果多个 key 的 hash 设计得很差。
                ### 2. 什么情况下会树化？
                桶内链表达到 8 且容量至少 64。
                ## 四、扩容和树化的关系
                扩容和树化不是互相替代，而是分层防御。
                """;
        List<KbQuestionDraft> drafts = List.of(
                draft("为什么需要扩容？", filler(260)),
                draft("JDK 8 如何迁移节点？", filler(280)),
                draft("为什么扩容有时解决不了冲突？", filler(160)),
                draft("什么情况下会树化？", filler(190)));
        KbDocumentKindGate.Verdict verdict = judge("HashMap扩容与树化专项总结.md", text, drafts);
        assertThat(verdict.questionBank()).isFalse();
        assertThat(verdict.summary()).contains("章级结构").contains("总结");
    }

    @Test
    void 面经一题一答算题库() {
        String text = """
                1. 介绍一下你做的项目
                答案：负责订单中台，QPS 3k。
                2. 为什么用 Redis 做缓存
                答案：读多写少，热点集中。
                3. 缓存击穿怎么处理
                答案：互斥锁 + 逻辑过期。
                4. 线程池参数怎么配
                答案：按 IO/CPU 比例。
                5. MySQL 索引失效有哪些场景
                答案：隐式转换、函数、前导模糊。
                6. 分布式锁怎么实现
                答案：SETNX + 过期 + 看门狗。
                面试官追问了三次项目细节。
                """;
        List<KbQuestionDraft> drafts = List.of(
                draft("介绍一下你做的项目", filler(300)),
                draft("为什么用 Redis 做缓存", filler(320)),
                draft("缓存击穿怎么处理", filler(340)),
                draft("线程池参数怎么配", filler(360)),
                draft("MySQL 索引失效有哪些场景", filler(380)),
                draft("分布式锁怎么实现", filler(400)));
        KbDocumentKindGate.Verdict verdict = judge("2026-09-26-某公司面经.md", text, drafts);
        assertThat(verdict.questionBank()).isTrue();
        assertThat(verdict.score()).isGreaterThanOrEqualTo(KbDocumentKindGate.BANK_THRESHOLD);
    }

    @Test
    void 陈述式题面的面试题集仍算题库() {
        String text = """
                1. 索引失效场景
                答案：隐式类型转换、函数包裹列、前导模糊匹配。
                2. 事务隔离级别
                答案：读未提交、读已提交、可重复读、串行化。
                3. MVCC 实现原理
                答案：隐藏列 + undo log + ReadView。
                4. 分库分表方案
                答案：按用户 ID 取模，跨库查询走聚合层。
                """;
        List<KbQuestionDraft> drafts = new ArrayList<>();
        drafts.add(draft("索引失效场景", filler(600)));
        drafts.add(draft("事务隔离级别", filler(600)));
        drafts.add(draft("MVCC 实现原理", filler(600)));
        drafts.add(draft("分库分表方案", filler(600)));
        KbDocumentKindGate.Verdict verdict = judge("MySQL面试题.md", text, drafts);
        assertThat(verdict.questionBank()).isTrue();
    }

    @Test
    void 边界区间模型不可用时保守判题库() {
        // 无关键词文件名 + 一半题面像问题 + 题号连续到 3 + 答案长度适中 → 落在 [0.40, 0.55]
        String text = "1. 什么是 AQS\n2. 介绍一下 B+ 树\n3. 锁升级过程\n";
        List<KbQuestionDraft> drafts = List.of(
                draft("什么是 AQS", filler(200)),
                draft("介绍一下 B+ 树", filler(200)),
                draft("锁升级过程", filler(200)),
                draft("volatile 内存语义", filler(200)));
        KbDocumentKindGate.Verdict verdict = judge("note-0042.md", text, drafts);
        assertThat(verdict.score()).isBetween(KbDocumentKindGate.NOTES_THRESHOLD, KbDocumentKindGate.BANK_THRESHOLD);
        // rulesOnly 不做模型判定，直接按"不是题库"处理
        assertThat(verdict.questionBank()).isFalse();

        // 真网关但模型不可用：保守判题库，避免误删一批题
        KbDocumentKindGate withModel = new KbDocumentKindGate(null, true, true);
        KbDocumentKindGate.Verdict fallback = withModel.judge("note-0042.md", text, drafts);
        assertThat(fallback.source()).isEqualTo("FALLBACK");
        assertThat(fallback.questionBank()).isTrue();
    }

    @Test
    void 解析器加闸门_讲义不出题_面经出题() {
        String notes = """
                # HashMap 扩容与树化专项总结
                ## 一、整体结构
                横向数组 + 纵向桶结构。
                ## 二、扩容
                ### 1. 为什么需要扩容？
                冲突概率上升。
                ### 2. JDK 8 如何迁移节点？
                按低位重新分配。
                """;
        List<KbChunk> chunks = List.of(KbChunk.create(1L, 1L, "c1", 0, "", null, 100, notes));
        List<KbQuestionDraft> notesDrafts = new KbQuestionParser().parse(notes, chunks);
        assertThat(notesDrafts).isNotEmpty();
        assertThat(gate.judge("HashMap扩容与树化专项总结.md", notes, notesDrafts).questionBank()).isFalse();

        String bank = """
                # 某公司面经
                1. 为什么用 Redis？
                答案：%s
                2. 缓存穿透怎么处理？
                答案：%s
                3. 线程池怎么配？
                答案：%s
                面试官一共追问了三轮。
                """.formatted(filler(120), filler(120), filler(120));
        List<KbChunk> bankChunks = List.of(KbChunk.create(1L, 2L, "c2", 0, "", null, 100, bank));
        List<KbQuestionDraft> bankDrafts = new KbQuestionParser().parse(bank, bankChunks);
        assertThat(bankDrafts).isNotEmpty();
        assertThat(gate.judge("2026-09-26-某公司面经.md", bank, bankDrafts).questionBank()).isTrue();
    }

    @Test
    void 题号连续段能识别讲义式重置() {
        assertThat(KbDocumentKindGate.longestNumberRun("1. a\n2. b\n3. c\n4. d")).isEqualTo(4);
        // 每章从 1 重来：连续段只有 2
        assertThat(KbDocumentKindGate.longestNumberRun("1. a\n2. b\n1. c\n2. d")).isEqualTo(2);
    }
}
