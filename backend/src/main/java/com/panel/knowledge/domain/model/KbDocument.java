package com.panel.knowledge.domain.model;

import com.panel.shared.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * 知识库文档：原文件在 MinIO，解析/切片产物在 {@code kb_chunk}，索引在 ES + Milvus。
 *
 * <p>状态流转：UPLOADING → PARSING → CHUNKED → INDEXING → READY，任一步失败 → FAILED（可重试）。</p>
 */
@Entity
@Table(name = "kb_document",
        uniqueConstraints = @UniqueConstraint(name = "uk_kb_document_hash", columnNames = {"kb_id", "content_hash"}),
        indexes = @Index(name = "idx_kb_document_kb_status", columnList = "kb_id,status"))
public class KbDocument extends BaseEntity {

    private static final int MAX_FILE_NAME = 255;
    private static final int MAX_CONTENT_TYPE = 128;
    private static final int MAX_OBJECT_KEY = 512;
    private static final int MAX_ERROR = 1000;

    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    @Column(name = "file_name", nullable = false, length = MAX_FILE_NAME)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = MAX_CONTENT_TYPE)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "object_key", nullable = false, length = MAX_OBJECT_KEY)
    private String objectKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, columnDefinition = "varchar(20)")
    private DocumentStatus status;

    @Column(name = "error_message", length = MAX_ERROR)
    private String errorMessage;

    @Column(name = "chunk_count", nullable = false)
    private int chunkCount;

    @Column(name = "token_count", nullable = false)
    private int tokenCount;

    @Column(nullable = false)
    private boolean published;

    @Column(name = "index_generation", length = 128)
    private String indexGeneration;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "indexed_at")
    private Instant indexedAt;

    protected KbDocument() {
    }

    private KbDocument(Long kbId, String fileName, String contentType, long sizeBytes, String contentHash,
                       String objectKey, Long createdBy) {
        if (kbId == null || kbId <= 0) {
            throw new IllegalArgumentException("知识库 id 不能为空");
        }
        if (fileName == null || fileName.isBlank() || fileName.length() > MAX_FILE_NAME) {
            throw new IllegalArgumentException("文件名不合法");
        }
        if (contentType == null || contentType.isBlank() || contentType.length() > MAX_CONTENT_TYPE) {
            throw new IllegalArgumentException("文件类型不合法");
        }
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("文件内容为空");
        }
        if (contentHash == null || contentHash.length() != 64) {
            throw new IllegalArgumentException("内容哈希不合法");
        }
        if (objectKey == null || objectKey.isBlank() || objectKey.length() > MAX_OBJECT_KEY) {
            throw new IllegalArgumentException("对象存储 key 不合法");
        }
        this.kbId = kbId;
        this.fileName = fileName.trim();
        this.contentType = contentType.trim();
        this.sizeBytes = sizeBytes;
        this.contentHash = contentHash;
        this.objectKey = objectKey;
        this.status = DocumentStatus.UPLOADING;
        this.createdBy = createdBy;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static KbDocument create(Long kbId, String fileName, String contentType, long sizeBytes,
                                    String contentHash, String objectKey, Long createdBy) {
        return new KbDocument(kbId, fileName, contentType, sizeBytes, contentHash, objectKey, createdBy);
    }

    public void markParsing() {
        requireNotDeleted();
        status = DocumentStatus.PARSING;
        errorMessage = null;
        touch();
    }

    public void markChunked(int chunks, int tokens) {
        if (status != DocumentStatus.PARSING) {
            throw new IllegalStateException("只有 PARSING 状态可以进入 CHUNKED");
        }
        if (chunks <= 0 || tokens <= 0) {
            throw new IllegalArgumentException("切片结果为空");
        }
        status = DocumentStatus.CHUNKED;
        chunkCount = chunks;
        tokenCount = tokens;
        touch();
    }

    public void markIndexing() {
        if (status != DocumentStatus.CHUNKED && status != DocumentStatus.INDEXING
                && status != DocumentStatus.PARSING) {
            throw new IllegalStateException("只有 CHUNKED/INDEXING 状态可以进入 INDEXING");
        }
        status = DocumentStatus.INDEXING;
        touch();
    }

    public void markReady(String generation) {
        if (generation == null || generation.isBlank()) {
            throw new IllegalArgumentException("索引代不能为空");
        }
        status = DocumentStatus.READY;
        indexGeneration = generation.trim();
        indexedAt = Instant.now();
        errorMessage = null;
        touch();
    }

    public void markFailed(String message) {
        status = DocumentStatus.FAILED;
        errorMessage = message == null || message.isBlank()
                ? "入库失败"
                : message.length() <= MAX_ERROR ? message : message.substring(0, MAX_ERROR);
        touch();
    }

    /** 重试：回到 UPLOADING，原文件仍在 MinIO，不重新上传。 */
    public void resetForRetry() {
        requireNotDeleted();
        status = DocumentStatus.UPLOADING;
        errorMessage = null;
        indexGeneration = null;
        chunkCount = 0;
        tokenCount = 0;
        published = false;
        touch();
    }

    /** 重建索引：保留已落库切片，回到 CHUNKED 让入库线程只重跑索引一段。 */
    public void markForReindex() {
        if (status != DocumentStatus.READY) {
            throw new IllegalStateException("只有 READY 的文档可以重建索引");
        }
        status = DocumentStatus.CHUNKED;
        errorMessage = null;
        indexedAt = null;
        touch();
    }

    public void publish() {
        if (status != DocumentStatus.READY) {
            throw new IllegalStateException("只有 READY 的文档可以发布");
        }
        published = true;
        touch();
    }

    public void unpublish() {
        published = false;
        touch();
    }

    public void markDeleted() {
        status = DocumentStatus.DELETED;
        published = false;
        // 删除后释放 (kb_id, content_hash) 唯一键，允许重新上传同一份文件。
        if (contentHash != null) {
            contentHash = "deleted-" + getId();
        }
        touch();
    }

    private void requireNotDeleted() {
        if (status == DocumentStatus.DELETED) {
            throw new IllegalStateException("文档已删除");
        }
    }

    private void touch() {
        updatedAt = Instant.now();
    }

    public Long kbId() { return kbId; }
    public String fileName() { return fileName; }
    public String contentType() { return contentType; }
    public long sizeBytes() { return sizeBytes; }
    public String contentHash() { return contentHash; }
    public String objectKey() { return objectKey; }
    public DocumentStatus status() { return status; }
    public String errorMessage() { return errorMessage; }
    public int chunkCount() { return chunkCount; }
    public int tokenCount() { return tokenCount; }
    public boolean published() { return published; }
    public String indexGeneration() { return indexGeneration; }
    public Long createdBy() { return createdBy; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public Instant indexedAt() { return indexedAt; }
}
