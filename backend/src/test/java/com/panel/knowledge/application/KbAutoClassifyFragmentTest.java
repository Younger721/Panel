package com.panel.knowledge.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 规则兜底的关键片段匹配：模型不可用时，至少要能把"名字带括号/斜杠"的节点命中上。
 */
class KbAutoClassifyFragmentTest {

    private static final String REDIS_TEXT = """
            redis 持久化 redis 提供 rdb 和 aof 两种持久化方式，生产环境常组合使用。
            rdb 是某一时刻的全量快照，文件紧凑、恢复快；aof 把每条写命令追加到日志，可配置 everysec 刷盘。
            """;

    private static final String KAFKA_TEXT = """
            消息队列与幂等 kafka 通过分区保证同一分区内消息有序，消费者组内每个分区只分配给一个消费者。
            生产者开启幂等后，broker 会用 pid 与序列号去重。
            """;

    @Test
    @DisplayName("带括号的复合节点名：RDB/AOF/持久化 命中，够线自动挂")
    void matchesCompoundNameByFragments() {
        double score = KbAutoClassifyService.fragmentScore(REDIS_TEXT, "持久化（RDB/AOF）");

        assertThat(score).isGreaterThanOrEqualTo(0.72);
        assertThat(score).isLessThanOrEqualTo(0.78);
    }

    @Test
    @DisplayName("只有一个片段命中时不给分：宁可留在未分类，也不要挂错")
    void needsAtLeastTwoFragments() {
        assertThat(KbAutoClassifyService.fragmentScore("只提到 kafka 一次", "持久化（RDB/AOF）")).isZero();
        assertThat(KbAutoClassifyService.fragmentScore(REDIS_TEXT, "消息队列与幂等")).isZero();
    }

    @Test
    @DisplayName("泛词不参与计分：'与/场景/处理' 这类不能把节点点亮")
    void ignoresStopWordFragments() {
        assertThat(KbAutoClassifyService.fragmentScore("缓存与一致性场景处理", "缓存（穿透/雪崩）")).isZero();
    }

    @Test
    @DisplayName("中文 2-gram 展开：讲 Kafka 的正文能命中「分区与消费组」")
    void chineseBigramsMatch() {
        // 正文里"分区""消费"都在，2-gram 各自命中 -> 够线
        assertThat(KbAutoClassifyService.fragmentScore(KAFKA_TEXT, "分区与消费组")).isGreaterThan(0);
        // 正文完全不讲缓存，任何字样都不该命中
        assertThat(KbAutoClassifyService.fragmentScore(KAFKA_TEXT, "缓存（穿透/雪崩）")).isZero();
        // 英文技术词单独命中不够，英文词 + 中文片段才够
        assertThat(KbAutoClassifyService.fragmentScore("kafka 集群架构说明", "Kafka 架构与存储"))
                .isGreaterThan(0);
        assertThat(KbAutoClassifyService.fragmentScore("只提到 kafka 一个词", "Kafka 架构与存储")).isZero();
    }
}
