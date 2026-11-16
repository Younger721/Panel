package com.panel.knowledge.application;

import java.util.List;

/** 选中父目录时，右侧按一级子目录分组展示；items 是该子目录子树内的一页预览。 */
public record KbCatalogGroup(
        Long id,
        String name,
        String pathKey,
        long total,
        List<KbCatalogItem> items) {
}
