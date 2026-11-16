package com.panel.knowledge.api.request;

public record CreateKbCategoryRequest(
        Long parentId,
        String name,
        String description,
        String pathKey) {
}
