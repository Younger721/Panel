package com.panel.knowledge.application;

import com.panel.knowledge.domain.model.KbChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KbQuestionParserTest {

    private final KbQuestionParser parser = new KbQuestionParser();

    @Test
    void splitsNumberedQuestionsAndKeepsMetadata() {
        KbChunk chunk = chunk("2023 字节 后端 一面",
                "2023 字节 后端 一面\n"
                        + "1. Redis 为什么用单线程？\n参考答案：避免锁竞争。\n"
                        + "2. 如何排查 OOM？\n参考答案：看堆 dump。");

        List<KbQuestionDraft> drafts = parser.parse(List.of(chunk));

        assertThat(drafts).hasSize(2);
        assertThat(drafts.get(0).stem()).isEqualTo("Redis 为什么用单线程？");
        assertThat(drafts.get(1).stem()).isEqualTo("如何排查 OOM？");
        assertThat(drafts.get(0).company()).isEqualTo("字节");
        assertThat(drafts.get(0).year()).isEqualTo(2023);
        assertThat(drafts.get(0).role()).isEqualTo("后端");
    }

    @Test
    void usesFirstQuestionLineWhenHeadingIsSectionTitle() {
        KbChunk chunk = chunk("2024 腾讯 后端",
                "2024 腾讯 后端\nRedis 为什么用单线程？\n参考答案：避免锁竞争。");

        List<KbQuestionDraft> drafts = parser.parse(List.of(chunk));

        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).stem()).isEqualTo("Redis 为什么用单线程？");
        assertThat(drafts.get(0).answer()).contains("避免锁竞争");
        assertThat(drafts.get(0).company()).isEqualTo("腾讯");
    }

    @Test
    void doesNotSplitNumberedListsInsideAnAnswer() {
        KbChunk chunk = chunk("RDB 持久化",
                "RDB 持久化\n恢复步骤：\n1. 加载快照\n2. 回放 AOF");

        List<KbQuestionDraft> drafts = parser.parse(List.of(chunk));

        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).stem()).isEqualTo("RDB 持久化");
        assertThat(drafts.get(0).answer()).contains("加载快照");
    }

    @Test
    void readsHashtagsFromAnswerBody() {
        KbChunk chunk = chunk("Redis 为什么快？",
                "Redis 为什么快？\n纯内存、单线程、IO 多路复用。\n#字节 #Redis #2024");

        List<KbQuestionDraft> drafts = parser.parse(List.of(chunk));

        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).tags()).contains("字节", "Redis", "2024");
        assertThat(drafts.get(0).company()).isEqualTo("字节");
        assertThat(drafts.get(0).year()).isEqualTo(2024);
    }

    @Test
    void skipsDocumentTitleChunkWithoutBody() {
        KbChunk chunk = chunk("2024 面试真题精选", "2024 面试真题精选");

        assertThat(parser.parse(List.of(chunk))).isEmpty();
    }

    @Test
    void keepsQuestionWithIndexInHeading() {
        KbChunk chunk = chunk("MySQL 索引为什么用 B+ 树？",
                "MySQL 索引为什么用 B+ 树？\nB+ 树树高更低，范围查询更快。\n#MySQL #索引");

        List<KbQuestionDraft> drafts = parser.parse(List.of(chunk));

        assertThat(drafts).hasSize(1);
        assertThat(drafts.get(0).stem()).isEqualTo("MySQL 索引为什么用 B+ 树？");
        assertThat(drafts.get(0).tags()).contains("MySQL", "索引");
    }

    private static KbChunk chunk(String heading, String text) {
        return KbChunk.create(1L, 2L, "chunk-" + heading, 0, heading, 1, 20, text);
    }
}
