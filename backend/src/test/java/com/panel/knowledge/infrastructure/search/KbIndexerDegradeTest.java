package com.panel.knowledge.infrastructure.search;

import com.panel.ai.domain.EmbeddingGateway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * ES / Milvus 没启动时的降级行为。
 *
 * <p>回归的 bug：文档删除返回 500「服务器内部错误」——{@code deleteByDocument} 里
 * {@code ensureEsIndex()} 直接连 ES，连不上就抛 ConnectException 冒到接口层。
 * 现在后端不可用只记一条 warn 并跳过，删除/重建索引/上传都不再被外部索引拖死。</p>
 */
class KbIndexerDegradeTest {

    /** 127.0.0.1:1 上没有服务，连接会立刻失败——模拟 ES/Milvus 未启动。 */
    private static final String DEAD = "http://127.0.0.1:1";

    private final EmbeddingGateway embedding = texts -> texts.stream()
            .map(text -> new float[1024])
            .toList();

    @Test
    @DisplayName("后端不可用时删除文档不抛异常（回归 500）")
    void deleteByDocumentDoesNotThrow() {
        KbIndexer indexer = new KbIndexer(embedding, DEAD, DEAD);
        assertThatCode(() -> indexer.deleteByDocument(1L)).doesNotThrowAnyException();
        assertThatCode(() -> indexer.deleteByKb(1L)).doesNotThrowAnyException();
        assertThatCode(() -> indexer.deleteByDocument(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("后端不可用时索引返回降级 generation（文档仍能进 READY）")
    void indexFallsBackToDegradedGeneration() {
        KbIndexer indexer = new KbIndexer(embedding, DEAD, DEAD);
        var document = com.panel.knowledge.domain.model.KbDocument.create(
                7L, "probe.md", "text/markdown", 10L,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "kb/7/probe.md", 1L);
        var chunk = com.panel.knowledge.domain.model.KbChunk.create(
                7L, 1L, "kb7-probe-0", 0, "小节", null, 3, "正文");

        String generation = indexer.index(document, List.of(chunk),
                com.panel.knowledge.domain.model.KbKind.TECH_DOC, List.of());

        assertThat(generation).startsWith(KbIndexer.DEGRADED_PREFIX);
    }
}
