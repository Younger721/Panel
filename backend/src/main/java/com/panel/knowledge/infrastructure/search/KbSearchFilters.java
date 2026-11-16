package com.panel.knowledge.infrastructure.search;

import java.util.List;

/** 检索过滤：题库元数据只对 BM25 通道生效；categoryIds 在融合后按目录子树过滤所有通道。 */
public record KbSearchFilters(String company, String difficulty, Integer year, List<String> tags,
                              List<Long> categoryIds) {

    public static KbSearchFilters none() {
        return new KbSearchFilters(null, null, null, List.of(), List.of());
    }

    public boolean isEmpty() {
        return blank(company) && blank(difficulty) && year == null && (tags == null || tags.isEmpty())
                && (categoryIds == null || categoryIds.isEmpty());
    }

    public boolean hasCategoryFilter() {
        return categoryIds != null && !categoryIds.isEmpty();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
