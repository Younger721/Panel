package com.panel.knowledge.domain.model;

import com.panel.shared.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * 知识目录节点：每个 KB 一棵树，parent_id = 0 表示根，最多 3 级。
 *
 * <p>{@code path} 按 id 生成（/1/7/），用于子树查询；{@code path_key} 是跨库稳定的语义键，
 * 供题库与技术文档对齐（如 {@code mysql/index}）。</p>
 */
@Entity
@Table(name = "kb_category",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_kb_category_parent_name", columnNames = {"kb_id", "parent_id", "name"}),
                @UniqueConstraint(name = "uk_kb_category_path_key", columnNames = {"kb_id", "path_key"})
        },
        indexes = @Index(name = "idx_kb_category_path", columnList = "kb_id,path"))
public class KbCategory extends BaseEntity {

    public static final long ROOT_PARENT_ID = 0L;
    public static final int MAX_DEPTH = 3;

    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    @Column(name = "parent_id", nullable = false)
    private Long parentId;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(nullable = false, length = 512)
    private String path;

    @Column(name = "path_key", nullable = false, length = 512)
    private String pathKey;

    @Column(nullable = false)
    private int depth;

    @Column(length = 512)
    private String description;

    @Column(name = "sort_no", nullable = false)
    private int sortNo;

    @Column(nullable = false, length = 16)
    private String source;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected KbCategory() {
    }

    private KbCategory(Long kbId, Long parentId, String name, String description, int sortNo) {
        if (kbId == null) {
            throw new IllegalArgumentException("目录缺少知识库");
        }
        this.kbId = kbId;
        this.parentId = parentId == null ? ROOT_PARENT_ID : parentId;
        this.name = requireName(name);
        this.description = trim(description, 512);
        this.sortNo = sortNo;
        this.source = "MANUAL";
        this.status = "ACTIVE";
        this.path = "/";
        this.pathKey = "";
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public static KbCategory create(Long kbId, Long parentId, String name, String description, int sortNo) {
        return new KbCategory(kbId, parentId, name, description, sortNo);
    }

    public static String requireName(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("目录名称不能为空");
        }
        if (value.length() > 64) {
            throw new IllegalArgumentException("目录名称不能超过 64 个字符");
        }
        return value;
    }

    public void rename(String name, String description) {
        this.name = requireName(name);
        this.description = trim(description, 512);
        touch();
    }

    public void applyPath(String path, String pathKey, int depth) {
        this.path = path;
        this.pathKey = pathKey;
        this.depth = depth;
        touch();
    }

    public void applyParent(Long parentId, String path, String pathKey, int depth) {
        this.parentId = parentId == null ? ROOT_PARENT_ID : parentId;
        applyPath(path, pathKey, depth);
    }

    /** 同级排序：拖拽调整顺序时只改这个数字（步长由调用方决定，通常 10）。 */
    public void reorder(int sortNo) {
        this.sortNo = sortNo;
        touch();
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    private static String trim(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String stripped = value.strip();
        return stripped.length() <= max ? stripped : stripped.substring(0, max);
    }

    public Long kbId() { return kbId; }
    public Long parentId() { return parentId; }
    public String name() { return name; }
    public String path() { return path; }
    public String pathKey() { return pathKey; }
    public int depth() { return depth; }
    public String description() { return description; }
    public int sortNo() { return sortNo; }
    public String source() { return source; }
    public String status() { return status; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
