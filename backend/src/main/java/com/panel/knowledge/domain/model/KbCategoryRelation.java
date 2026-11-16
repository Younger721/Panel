package com.panel.knowledge.domain.model;

import com.panel.shared.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.Set;

/**
 * 目录节点之间的横切关系：树负责“包含”，这张表负责“相关 / 前置 / 对比”。
 *
 * <p>刻意只做节点级、无向读、1 跳展示——不引入实体级图谱（概念、关系、证据块），
 * 那套的成本（逐片 LLM 抽取）远大于它在这个产品里的收益。</p>
 */
@Entity
@Table(name = "kb_category_relation",
        uniqueConstraints = @UniqueConstraint(name = "uk_kb_category_relation",
                columnNames = {"from_category_id", "to_category_id", "relation"}),
        indexes = {
                @Index(name = "idx_kb_category_relation_from", columnList = "kb_id,from_category_id"),
                @Index(name = "idx_kb_category_relation_to", columnList = "kb_id,to_category_id")
        })
public class KbCategoryRelation extends BaseEntity {

    public static final String RELATED = "RELATED";
    public static final String PREREQUISITE = "PREREQUISITE";
    public static final String CONTRAST = "CONTRAST";
    public static final Set<String> TYPES = Set.of(RELATED, PREREQUISITE, CONTRAST);

    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    @Column(name = "from_category_id", nullable = false)
    private Long fromCategoryId;

    @Column(name = "to_category_id", nullable = false)
    private Long toCategoryId;

    @Column(nullable = false, length = 24)
    private String relation;

    @Column(nullable = false, columnDefinition = "decimal(3,2)")
    private Double weight;

    @Column(nullable = false, length = 16)
    private String source;

    @Column(length = 256)
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected KbCategoryRelation() {
    }

    private KbCategoryRelation(Long kbId, Long fromCategoryId, Long toCategoryId, String relation,
                               Double weight, String source, String note) {
        if (kbId == null || fromCategoryId == null || toCategoryId == null) {
            throw new IllegalArgumentException("关系缺少节点");
        }
        if (fromCategoryId.equals(toCategoryId)) {
            throw new IllegalArgumentException("不能把知识点连到自己");
        }
        String type = relation == null || relation.isBlank() ? RELATED : relation.trim().toUpperCase();
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("不支持的关系类型：" + relation);
        }
        this.kbId = kbId;
        this.fromCategoryId = fromCategoryId;
        this.toCategoryId = toCategoryId;
        this.relation = type;
        double value = weight == null ? 0.5d : weight;
        this.weight = Math.max(0d, Math.min(1d, value));
        this.source = source == null || source.isBlank() ? "MANUAL" : source.trim().toUpperCase();
        this.note = note == null || note.isBlank() ? null : note.trim();
        this.createdAt = Instant.now();
    }

    public static KbCategoryRelation create(Long kbId, Long fromCategoryId, Long toCategoryId,
                                            String relation, Double weight, String note) {
        return new KbCategoryRelation(kbId, fromCategoryId, toCategoryId, relation, weight, "MANUAL", note);
    }

    public void reweight(Double weight, String note) {
        if (weight != null) {
            this.weight = Math.max(0d, Math.min(1d, weight));
        }
        if (note != null) {
            this.note = note.isBlank() ? null : note.trim();
        }
    }

    public Long kbId() { return kbId; }
    public Long fromCategoryId() { return fromCategoryId; }
    public Long toCategoryId() { return toCategoryId; }
    public String relation() { return relation; }
    public Double weight() { return weight; }
    public String source() { return source; }
    public String note() { return note; }
    public Instant createdAt() { return createdAt; }
}
