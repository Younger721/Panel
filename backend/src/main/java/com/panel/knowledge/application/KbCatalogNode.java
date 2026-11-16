package com.panel.knowledge.application;

import java.util.List;

/** 目录树节点；chunkCount/questionCount 含子节点。 */
public record KbCatalogNode(
        Long id,
        Long parentId,
        String name,
        String pathKey,
        int depth,
        long chunkCount,
        long questionCount,
        boolean system,
        String description,
        List<KbCatalogNode> children) {
}
