package com.panel.knowledge.domain.model;

import com.panel.shared.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * 知识库：文档与切片的归属单位，也是检索过滤的第一级。
 *
 * <p>单租户产品，没有 tenant_id；内置库由启动种子创建，不能删除。</p>
 */
@Entity
@Table(name = "kb_base", uniqueConstraints = @UniqueConstraint(name = "uk_kb_base_code", columnNames = "code"))
public class KnowledgeBase extends BaseEntity {

    private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9-]{1,63}");
    private static final int MAX_NAME = 128;
    private static final int MAX_DESCRIPTION = 512;

    @Column(nullable = false, length = 64)
    private String code;

    @Column(nullable = false, length = MAX_NAME)
    private String name;

    @Column(length = MAX_DESCRIPTION)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32, columnDefinition = "varchar(32)")
    private KbKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, columnDefinition = "varchar(16)")
    private KbVisibility visibility;

    @Enumerated(EnumType.STRING)
    @Column(name = "chunk_strategy", nullable = false, length = 32, columnDefinition = "varchar(32)")
    private KbChunkStrategy chunkStrategy;

    @Column(name = "chunk_size", nullable = false)
    private int chunkSize;

    @Column(name = "chunk_overlap", nullable = false)
    private int chunkOverlap;

    @Column(name = "top_k", nullable = false)
    private int topK;

    @Column(name = "rerank_enabled", nullable = false)
    private boolean rerankEnabled;

    @Column(name = "score_threshold", nullable = false, precision = 4, scale = 3)
    private BigDecimal scoreThreshold;

    @Column(nullable = false)
    private boolean builtin;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected KnowledgeBase() {
    }

    private KnowledgeBase(String code, String name, String description, KbKind kind, KbVisibility visibility,
                          KbChunkStrategy chunkStrategy, int chunkSize, int chunkOverlap, int topK,
                          boolean rerankEnabled, BigDecimal scoreThreshold, boolean builtin, Long createdBy) {
        this.code = normalizeCode(code);
        this.name = normalizeName(name);
        this.description = normalizeDescription(description);
        this.kind = requireKind(kind);
        this.visibility = requireVisibility(visibility);
        this.chunkStrategy = requireStrategy(chunkStrategy, this.kind);
        this.chunkSize = requireChunkSize(chunkSize);
        this.chunkOverlap = requireOverlap(chunkOverlap, this.chunkSize);
        this.topK = requireTopK(topK);
        this.rerankEnabled = rerankEnabled;
        this.scoreThreshold = requireThreshold(scoreThreshold);
        this.builtin = builtin;
        this.createdBy = createdBy;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static KnowledgeBase create(String code, String name, String description, KbKind kind,
                                       KbVisibility visibility, KbChunkStrategy chunkStrategy,
                                       Integer chunkSize, Integer chunkOverlap, Integer topK,
                                       Boolean rerankEnabled, BigDecimal scoreThreshold, Long createdBy) {
        KbKind actualKind = requireKind(kind);
        return new KnowledgeBase(code, name, description, actualKind, visibility,
                chunkStrategy == null ? KbChunkStrategy.defaultFor(actualKind) : chunkStrategy,
                chunkSize == null ? 500 : chunkSize,
                chunkOverlap == null ? 60 : chunkOverlap,
                topK == null ? 8 : topK,
                rerankEnabled == null || rerankEnabled,
                scoreThreshold == null ? new BigDecimal("0.200") : scoreThreshold,
                false, createdBy);
    }

    /** 启动种子用：内置库不可删除，createdBy 为空。 */
    public static KnowledgeBase builtin(String code, String name, String description, KbKind kind,
                                        KbVisibility visibility, KbChunkStrategy chunkStrategy,
                                        int chunkSize, int chunkOverlap, int topK, String threshold) {
        return new KnowledgeBase(code, name, description, kind, visibility, chunkStrategy,
                chunkSize, chunkOverlap, topK, true, new BigDecimal(threshold), true, null);
    }

    public void rename(String name, String description) {
        this.name = normalizeName(name);
        this.description = normalizeDescription(description);
        touch();
    }

    public void updateSettings(KbVisibility visibility, KbChunkStrategy chunkStrategy, Integer chunkSize,
                               Integer chunkOverlap, Integer topK, Boolean rerankEnabled, BigDecimal scoreThreshold) {
        if (visibility != null) {
            this.visibility = requireVisibility(visibility);
        }
        if (chunkStrategy != null || chunkSize != null || chunkOverlap != null) {
            KbChunkStrategy next = chunkStrategy == null ? this.chunkStrategy : chunkStrategy;
            int nextSize = chunkSize == null ? this.chunkSize : requireChunkSize(chunkSize);
            int nextOverlap = chunkOverlap == null ? this.chunkOverlap : requireOverlap(chunkOverlap, nextSize);
            this.chunkStrategy = next;
            this.chunkSize = nextSize;
            this.chunkOverlap = nextOverlap;
        }
        if (topK != null) {
            this.topK = requireTopK(topK);
        }
        if (rerankEnabled != null) {
            this.rerankEnabled = rerankEnabled;
        }
        if (scoreThreshold != null) {
            this.scoreThreshold = requireThreshold(scoreThreshold);
        }
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    private static String normalizeCode(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("知识库 code 不能为空");
        }
        String value = raw.trim().toLowerCase();
        if (!CODE.matcher(value).matches()) {
            throw new IllegalArgumentException("知识库 code 只能是小写字母/数字/短横线，2-64 位且以字母开头");
        }
        return value;
    }

    private static String normalizeName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("知识库名称不能为空");
        }
        String value = raw.trim();
        if (value.length() > MAX_NAME) {
            throw new IllegalArgumentException("知识库名称不能超过 " + MAX_NAME + " 个字符");
        }
        return value;
    }

    private static String normalizeDescription(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() > MAX_DESCRIPTION) {
            throw new IllegalArgumentException("知识库描述不能超过 " + MAX_DESCRIPTION + " 个字符");
        }
        return value;
    }

    private static KbKind requireKind(KbKind kind) {
        if (kind == null) {
            throw new IllegalArgumentException("知识库类型不能为空");
        }
        return kind;
    }

    private static KbVisibility requireVisibility(KbVisibility visibility) {
        if (visibility == null) {
            throw new IllegalArgumentException("可见性不能为空");
        }
        return visibility;
    }

    private static KbChunkStrategy requireStrategy(KbChunkStrategy strategy, KbKind kind) {
        return strategy == null ? KbChunkStrategy.defaultFor(kind) : strategy;
    }

    private static int requireChunkSize(int chunkSize) {
        if (chunkSize < 100 || chunkSize > 2000) {
            throw new IllegalArgumentException("切片大小需在 100-2000 token 之间");
        }
        return chunkSize;
    }

    private static int requireOverlap(int chunkOverlap, int chunkSize) {
        if (chunkOverlap < 0 || chunkOverlap > chunkSize / 2) {
            throw new IllegalArgumentException("切片重叠需在 0 到切片大小的一半之间");
        }
        return chunkOverlap;
    }

    private static int requireTopK(int topK) {
        if (topK < 1 || topK > 30) {
            throw new IllegalArgumentException("topK 需在 1-30 之间");
        }
        return topK;
    }

    private static BigDecimal requireThreshold(BigDecimal threshold) {
        if (threshold == null) {
            throw new IllegalArgumentException("分数阈值不能为空");
        }
        if (threshold.signum() < 0 || threshold.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("分数阈值需在 0-1 之间");
        }
        return threshold.setScale(3, java.math.RoundingMode.HALF_UP);
    }

    public String code() { return code; }
    public String name() { return name; }
    public String description() { return description; }
    public KbKind kind() { return kind; }
    public KbVisibility visibility() { return visibility; }
    public KbChunkStrategy chunkStrategy() { return chunkStrategy; }
    public int chunkSize() { return chunkSize; }
    public int chunkOverlap() { return chunkOverlap; }
    public int topK() { return topK; }
    public boolean rerankEnabled() { return rerankEnabled; }
    public BigDecimal scoreThreshold() { return scoreThreshold; }
    public boolean isBuiltin() { return builtin; }
    public Long createdBy() { return createdBy; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
