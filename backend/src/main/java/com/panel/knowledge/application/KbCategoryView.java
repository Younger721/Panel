package com.panel.knowledge.application;

/** 新建/编辑后返回的目录节点（不带 children）。 */
public record KbCategoryView(
        Long id,
        Long parentId,
        String name,
        String pathKey,
        String path,
        int depth,
        String description) {
}
