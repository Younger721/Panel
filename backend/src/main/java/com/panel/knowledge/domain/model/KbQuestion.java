package com.panel.knowledge.domain.model;

import com.panel.shared.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 题库中的一道题：题干是检索主字段，答案与标签是补充。
 *
 * <p>由 {@code KbQuestionParser} 从题库 md 里拆出来；{@code fingerprint} 用于近似重复题去重。</p>
 */
@Entity
@Table(name = "kb_question", indexes = {
        @Index(name = "idx_kb_question_kb", columnList = "kb_id,difficulty"),
        @Index(name = "idx_kb_question_doc", columnList = "document_id"),
        @Index(name = "idx_kb_question_chunk", columnList = "chunk_id"),
        @Index(name = "idx_kb_question_fingerprint", columnList = "kb_id,fingerprint")
})
public class KbQuestion extends BaseEntity {

    private static final int MAX_STEM = 4000;
    private static final int MAX_COMPANY = 64;
    private static final int MAX_ROLE = 64;
    private static final int MAX_DIFFICULTY = 16;

    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "chunk_id")
    private Long chunkId;

    @Column(nullable = false, columnDefinition = "text")
    private String stem;

    @Column(columnDefinition = "longtext")
    private String answer;

    @Column(length = MAX_DIFFICULTY)
    private String difficulty;

    @Column(length = MAX_COMPANY)
    private String company;

    @Column(length = MAX_ROLE)
    private String role;

    @Column(name = "question_year")
    private Integer year;

    @Column(name = "tags_json", columnDefinition = "text")
    private String tagsJson;

    @Column(length = 64)
    private String fingerprint;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected KbQuestion() {
    }

    private KbQuestion(Long kbId, Long documentId, Long chunkId, String stem, String answer,
                       String difficulty, String company, String role, Integer year, String tagsJson) {
        if (kbId == null || documentId == null) {
            throw new IllegalArgumentException("题目缺少知识库或文档");
        }
        if (stem == null || stem.isBlank()) {
            throw new IllegalArgumentException("题干不能为空");
        }
        this.kbId = kbId;
        this.documentId = documentId;
        this.chunkId = chunkId;
        this.stem = truncate(stem.strip(), MAX_STEM);
        this.answer = answer == null || answer.isBlank() ? null : answer.strip();
        this.difficulty = truncate(difficulty, MAX_DIFFICULTY);
        this.company = truncate(company, MAX_COMPANY);
        this.role = truncate(role, MAX_ROLE);
        this.year = year;
        this.tagsJson = tagsJson;
        this.fingerprint = fingerprint(this.stem);
        this.createdAt = Instant.now();
    }

    public static KbQuestion create(Long kbId, Long documentId, Long chunkId, String stem, String answer,
                                    String difficulty, String company, String role, Integer year,
                                    String tagsJson) {
        return new KbQuestion(kbId, documentId, chunkId, stem, answer, difficulty, company, role, year, tagsJson);
    }

    /** 题干归一化指纹：去空白、标点、大小写，用于发现近似重复题。 */
    public static String fingerprint(String stem) {
        String normalized = stem == null ? "" : stem.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}，。！？；：、“”‘’（）【】《》…—]", "");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException ex) {
            return Integer.toHexString(normalized.hashCode());
        }
    }

    private static String truncate(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    public Long kbId() { return kbId; }
    public Long documentId() { return documentId; }
    public Long chunkId() { return chunkId; }
    public String stem() { return stem; }
    public String answer() { return answer; }
    public String difficulty() { return difficulty; }
    public String company() { return company; }
    public String role() { return role; }
    public Integer year() { return year; }
    public String tagsJson() { return tagsJson; }
    public String fingerprint() { return fingerprint; }
    public Instant createdAt() { return createdAt; }
}
