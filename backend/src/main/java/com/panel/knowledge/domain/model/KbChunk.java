package com.panel.knowledge.domain.model;

import com.panel.shared.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/** 文档切片：ES/Milvus 的向量与文本来源，切片内容以这里为准。 */
@Entity
@Table(name = "kb_chunk",
        uniqueConstraints = @UniqueConstraint(name = "uk_kb_chunk_key", columnNames = "chunk_key"),
        indexes = @Index(name = "idx_kb_chunk_doc", columnList = "document_id,ordinal"))
public class KbChunk extends BaseEntity {

    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "chunk_key", nullable = false, length = 128)
    private String chunkKey;

    @Column(nullable = false)
    private int ordinal;

    @Column(length = 512)
    private String heading;

    @Column(name = "page_no")
    private Integer pageNo;

    @Column(name = "token_count", nullable = false)
    private int tokenCount;

    @Column(nullable = false, columnDefinition = "longtext")
    private String text;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected KbChunk() {
    }

    private KbChunk(Long kbId, Long documentId, String chunkKey, int ordinal, String heading, Integer pageNo,
                    int tokenCount, String text) {
        if (kbId == null || documentId == null || chunkKey == null || chunkKey.isBlank()) {
            throw new IllegalArgumentException("切片关联信息不完整");
        }
        if (ordinal < 0 || tokenCount <= 0 || text == null || text.isBlank()) {
            throw new IllegalArgumentException("切片内容不合法");
        }
        this.kbId = kbId;
        this.documentId = documentId;
        this.chunkKey = chunkKey;
        this.ordinal = ordinal;
        this.heading = heading == null || heading.isBlank()
                ? null
                : heading.length() <= 512 ? heading : heading.substring(0, 512);
        this.pageNo = pageNo;
        this.tokenCount = tokenCount;
        this.text = text;
        this.createdAt = Instant.now();
    }

    public static KbChunk create(Long kbId, Long documentId, String chunkKey, int ordinal, String heading,
                                 Integer pageNo, int tokenCount, String text) {
        return new KbChunk(kbId, documentId, chunkKey, ordinal, heading, pageNo, tokenCount, text);
    }

    public Long kbId() { return kbId; }
    public Long documentId() { return documentId; }
    public String chunkKey() { return chunkKey; }
    public int ordinal() { return ordinal; }
    public String heading() { return heading; }
    public Integer pageNo() { return pageNo; }
    public int tokenCount() { return tokenCount; }
    public String text() { return text; }
    public Instant createdAt() { return createdAt; }
}
