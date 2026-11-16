package com.panel.knowledge.domain.model;

import com.panel.shared.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * 共享概念：题库和技术文档引用同一行，是双库联动的桥。
 *
 * <p>{@code normalized_name} 唯一；别名放在 {@code aliases_json}，查询时统一映射到 canonical。</p>
 */
@Entity
@Table(name = "kb_concept",
        uniqueConstraints = @UniqueConstraint(name = "uk_kb_concept_normalized", columnNames = "normalized_name"),
        indexes = @Index(name = "idx_kb_concept_type", columnList = "type"))
public class KbConcept extends BaseEntity {

    private static final int MAX_NAME = 128;
    private static final int MAX_DESCRIPTION = 512;

    @Column(name = "canonical_name", nullable = false, length = MAX_NAME)
    private String canonicalName;

    @Column(name = "normalized_name", nullable = false, length = MAX_NAME)
    private String normalizedName;

    @Column(nullable = false, length = 24)
    private String type;

    @Column(length = MAX_DESCRIPTION)
    private String description;

    @Column(name = "aliases_json", columnDefinition = "text")
    private String aliasesJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected KbConcept() {
    }

    private KbConcept(String canonicalName, String type, String description, String aliasesJson) {
        if (canonicalName == null || canonicalName.isBlank()) {
            throw new IllegalArgumentException("概念名不能为空");
        }
        this.canonicalName = canonicalName.strip().substring(0, Math.min(MAX_NAME, canonicalName.strip().length()));
        this.normalizedName = normalize(canonicalName);
        this.type = type == null || type.isBlank() ? "CONCEPT" : type.strip().toUpperCase(Locale.ROOT);
        this.description = description == null || description.isBlank()
                ? null
                : description.strip().substring(0, Math.min(MAX_DESCRIPTION, description.strip().length()));
        this.aliasesJson = aliasesJson == null || aliasesJson.isBlank() ? "[]" : aliasesJson;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public static KbConcept create(String canonicalName, String type, String description, String aliasesJson) {
        return new KbConcept(canonicalName, type, description, aliasesJson);
    }

    public void merge(String description, String aliasesJson) {
        if (description != null && !description.isBlank() && (this.description == null
                || description.length() > this.description.length())) {
            this.description = description.strip().substring(0,
                    Math.min(MAX_DESCRIPTION, description.strip().length()));
        }
        if (aliasesJson != null && !aliasesJson.isBlank() && !"[]".equals(aliasesJson)) {
            this.aliasesJson = aliasesJson;
        }
        this.updatedAt = Instant.now();
    }

    /** 概念名归一化：小写、去空白与标点，保留 + # . 以免把 C++ 和 C 混成一个。 */
    public static String normalize(String name) {
        if (name == null) {
            return "";
        }
        return name.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsHan}a-z0-9+#.]", "");
    }

    public static String aliasesJson(List<String> aliases) {
        if (aliases == null || aliases.isEmpty()) {
            return "[]";
        }
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < aliases.size(); i++) {
            String value = aliases.get(i);
            if (value == null || value.isBlank()) {
                continue;
            }
            if (builder.length() > 1) {
                builder.append(',');
            }
            builder.append('"').append(value.replace("\"", "\\\"")).append('"');
        }
        return builder.append(']').toString();
    }

    public String canonicalName() { return canonicalName; }
    public String normalizedName() { return normalizedName; }
    public String type() { return type; }
    public String description() { return description; }
    public String aliasesJson() { return aliasesJson; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
