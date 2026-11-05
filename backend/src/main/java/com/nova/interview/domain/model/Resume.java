package com.nova.interview.domain.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 一份上传的简历原文。
 *
 * <p>单租户产品，刻意<b>不带 tenant_id</b>：多租户是上一个系统的底座遗留，
 * 本产品永远只有一个租户，加字段只会让每个查询都要多一个恒等条件。</p>
 *
 * <p>状态机：{@code PENDING → PARSING → PARSED / FAILED}。原文与解析结果分表
 * （{@link ResumeProfile}），因为原文是不可变字节，解析结果会随模型升级重跑。</p>
 */
@Entity
@Table(name = "resume")
public class Resume {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String fileName;

    @Column(nullable = false, length = 128)
    private String contentType;

    @Column(nullable = false, length = 512)
    private String objectKey;

    /** 原文 SHA-256，用于同文件重复上传检测。 */
    @Column(nullable = false, length = 64)
    private String contentHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ResumeStatus status = ResumeStatus.PENDING;

    @Column(length = 500)
    private String lastError;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    protected Resume() {
    }

    public static Resume create(String fileName, String contentType, String objectKey, String contentHash) {
        Resume resume = new Resume();
        resume.fileName = fileName;
        resume.contentType = contentType;
        resume.objectKey = objectKey;
        resume.contentHash = contentHash;
        resume.status = ResumeStatus.PENDING;
        return resume;
    }

    public void markParsing() {
        this.status = ResumeStatus.PARSING;
        this.lastError = null;
        touch();
    }

    public void markParsed() {
        this.status = ResumeStatus.PARSED;
        this.lastError = null;
        touch();
    }

    public void markFailed(String error) {
        this.status = ResumeStatus.FAILED;
        this.lastError = truncate(error);
        touch();
    }

    /** 重试未耗尽时退回待解析，让租约再次生效。 */
    public void markRetryable(String error) {
        this.status = ResumeStatus.PENDING;
        this.lastError = truncate(error);
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 500 ? value.substring(0, 500) : value;
    }

    public Long getId() {
        return id;
    }

    public String getFileName() {
        return fileName;
    }

    public String getContentType() {
        return contentType;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public String getContentHash() {
        return contentHash;
    }

    public ResumeStatus getStatus() {
        return status;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
