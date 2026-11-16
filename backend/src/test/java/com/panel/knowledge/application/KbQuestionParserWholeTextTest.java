package com.panel.knowledge.application;

import com.panel.knowledge.domain.model.KbChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 题库解析：一份 8 题的文档被切成 4 片后，必须还是 8 道题。
 *
 * <p>曾经按切片逐段解析，题目跨切片就丢——真实现场是 8 题只出 2 题，外加一条把文档标题
 * 当题干的垃圾数据。现在先按整篇拆题，再反查题落在哪一片。</p>
 */
class KbQuestionParserWholeTextTest {

    private static final List<String> QUESTIONS = List.of(
            "synchronized 和 ReentrantLock 有什么区别？各自适合什么场景？",
            "ThreadLocal 为什么会内存泄漏？怎么避免？",
            "线程池的核心参数怎么配？队列满了会怎样？",
            "JVM 的分代回收为什么要分新生代和老年代？",
            "CMS 和 G1 的区别是什么？现在该选哪个？",
            "MySQL 为什么用 B+ 树而不是 B 树或哈希索引？",
            "Redis 的 RDB 和 AOF 该怎么选？",
            "消息队列怎么保证消息不丢、不重复？");

    private static final String DOCUMENT = buildDocument();

    /**
     * 陈述式题号（不是疑问句）也必须算新题。
     *
     * <p>真实现场：「5. Java 数组常用方法，以及 List 的常用方法」不含任何疑问词，
     * 被当成答案正文，于是上一题「MyBatis 中 # 和 $ 的区别」的答案一路吞进 AOP、数组、List 三节。</p>
     */
    @Test
    @DisplayName("题号连续时，陈述式标题也算新题")
    void 陈述式题号不被吞进上一题答案() {
        String document = """
                1. VARCHAR 和 CHAR 的区别？
                简短回答：VARCHAR 变长，CHAR 定长。

                2. MyBatis 中 # 和 $ 的区别？
                #{} 是预编译参数。

                ```sql
                SELECT * FROM user WHERE id = #{id}
                ```

                3. Java 数组常用方法
                数组长度固定，常见操作通过 Arrays 完成。

                4. List 常用方法
                ArrayList 增删查改。
                """;
        List<KbQuestionDraft> parsed = new KbQuestionParser().parse(document, List.of());
        assertThat(parsed).hasSize(4);
        assertThat(parsed.get(1).answer()).contains("#{id}");
        assertThat(parsed.get(1).answer()).doesNotContain("Java 数组常用方法");
        assertThat(parsed.get(2).stem()).isEqualTo("Java 数组常用方法");
        assertThat(parsed.get(3).stem()).isEqualTo("List 常用方法");
    }

    @Test
    @DisplayName("答案里的有序列表从 1 重来，不当成新题")
    void 答案内有序列表不拆题() {
        String document = """
                1. 线程池的核心参数怎么配？
                分成三步：
                1. 先定核心线程数
                2. 再定队列容量
                3. 最后定拒绝策略

                2. Redis 的 RDB 和 AOF 该怎么选？
                看能否接受丢数据。
                """;
        List<KbQuestionDraft> parsed = new KbQuestionParser().parse(document, List.of());
        assertThat(parsed).hasSize(2);
        assertThat(parsed.get(0).answer()).contains("先定核心线程数").contains("最后定拒绝策略");
    }

    private static String buildDocument() {
        StringBuilder sb = new StringBuilder("# Java 并发与 JVM 面试 8 问\n\n");
        for (int i = 0; i < QUESTIONS.size(); i++) {
            sb.append(i + 1).append(". ").append(QUESTIONS.get(i)).append("\n\n");
            sb.append("这是第 ").append(i + 1).append(" 题的答案，用来说明原理与取舍，长度大约几十个字，")
                    .append("涉及实现机制、适用场景与常见坑位，生产环境里要结合压测数据判断。\n\n");
        }
        sb.append("#Java #并发 #Redis\n");
        return sb.toString();
    }

    /** 按固定长度粗暴切，制造"题目跨切片"的真实场景。 */
    private static List<KbChunk> chunks(int pieceSize) {
        List<KbChunk> result = new ArrayList<>();
        String text = DOCUMENT;
        int ordinal = 0;
        for (int start = 0; start < text.length(); start += pieceSize) {
            String piece = text.substring(start, Math.min(text.length(), start + pieceSize));
            KbChunk chunk = KbChunk.create(1L, 1L, UUID.randomUUID().toString(), ordinal++, "Java 并发与 JVM 面试 8 问",
                    null, piece.length() / 2, piece);
            // 未持久化的实体没有 id，但"题目挂回切片"靠的就是 id，所以补一个
            ReflectionTestUtils.setField(chunk, "id", 500L + ordinal);
            result.add(chunk);
        }
        return result;
    }

    @Test
    @DisplayName("整篇拆题：切成 4 片后仍然出 8 题，不丢不串")
    void wholeTextParsingKeepsAllQuestions() {
        List<KbChunk> slices = chunks(DOCUMENT.length() / 4 + 1);
        assertThat(slices).hasSizeGreaterThan(2);

        List<KbQuestionDraft> drafts = new KbQuestionParser().parse(DOCUMENT, slices);

        assertThat(drafts).hasSize(QUESTIONS.size());
        assertThat(drafts).extracting(KbQuestionDraft::stem).containsExactlyElementsOf(QUESTIONS);
        // 每道题都能挂回某一片，review 时点得进原文
        assertThat(drafts).allSatisfy(draft -> assertThat(draft.chunkId()).isNotNull());
    }

    @Test
    @DisplayName("整篇解析不会把文档标题或答案中段当成题干")
    void noGarbageStems() {
        List<KbQuestionDraft> drafts = new KbQuestionParser().parse(DOCUMENT, chunks(400));

        assertThat(drafts).extracting(KbQuestionDraft::stem)
                .doesNotContain("Java 并发与 JVM 面试 8 问")
                .allSatisfy(stem -> assertThat(stem).endsWith("？"));
    }

    @Test
    @DisplayName("切片切得更碎也不影响题数（切片只影响挂链接）")
    void fragmentationDoesNotChangeCount() {
        for (int size : Arrays.asList(150, 300, 600, 2000)) {
            assertThat(new KbQuestionParser().parse(DOCUMENT, chunks(size)))
                    .as("piece=%d", size)
                    .hasSize(QUESTIONS.size());
        }
    }
}
