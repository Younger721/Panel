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
 * 条目到目录的归属：一个切片/题目可以挂多个目录，{@code primary} 用于树计数与默认展示。
 *
 * <p>{@code locked=true} 表示人工确认过，后续 LLM 重新归类时跳过。</p>
 */
@Entity
@Table(name = "kb_item_category",
        uniqueConstraints = @UniqueConstraint(name = "uk_kb_item_category",
                columnNames = {"item_type", "item_id", "category_id"}),
        indexes = {
                @Index(name = "idx_kb_item_category_cat", columnList = "category_id,item_type"),
                @Index(name = "idx_kb_item_category_item", columnList = "item_type,item_id")
        })
public class KbItemCategory extends BaseEntity {

    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", nullable = false, length = 16)
    private KbItemType itemType;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(name = "is_primary", nullable = false)
    private boolean primaryFlag;

    @Column(columnDefinition = "decimal(4,3)")
    private Double confidence;

    @Column(nullable = false, length = 16)
    private String source;

    @Column(nullable = false)
    private boolean locked;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected KbItemCategory() {
    }

    private KbItemCategory(Long kbId, KbItemType itemType, Long itemId, Long categoryId,
                           boolean primary, Double confidence, String source, boolean locked) {
        this.kbId = kbId;
        this.itemType = itemType;
        this.itemId = itemId;
        this.categoryId = categoryId;
        this.primaryFlag = primary;
        this.confidence = confidence;
        this.source = source;
        this.locked = locked;
        this.createdAt = Instant.now();
    }

    /**
     * 人工挂载 / 人工挪动：直接锁定，置信度拉满。
     *
     * <p>人工动过的东西不该被下一次自动归类推翻——所以 {@code locked=true} 是默认，不是可选项。</p>
     */
    public static KbItemCategory manual(Long kbId, KbItemType itemType, Long itemId, Long categoryId,
                                        boolean primary) {
        return new KbItemCategory(kbId, itemType, itemId, categoryId, primary, 1.0d, "MANUAL", true);
    }

    /** LLM 或规则自动归类：未锁定，后续可被人工修正。 */
    public static KbItemCategory suggested(Long kbId, KbItemType itemType, Long itemId, Long categoryId,
                                           double confidence, boolean primary) {
        return new KbItemCategory(kbId, itemType, itemId, categoryId, primary, confidence, "LLM", false);
    }

    public void setPrimary(boolean primary) {
        this.primaryFlag = primary;
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
    }

    /** 人工确认归属：锁定 + 置信度拉满 + 换成 MANUAL，后续 LLM 重分类会跳过它。 */
    public void confirm() {
        this.locked = true;
        this.confidence = 1.0;
        this.source = "MANUAL";
    }

    public Long kbId() { return kbId; }
    public KbItemType itemType() { return itemType; }
    public Long itemId() { return itemId; }
    public Long categoryId() { return categoryId; }
    public boolean primary() { return primaryFlag; }
    public Double confidence() { return confidence; }
    public String source() { return source; }
    public boolean locked() { return locked; }
    public Instant createdAt() { return createdAt; }
}
