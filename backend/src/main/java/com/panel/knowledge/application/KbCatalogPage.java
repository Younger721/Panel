package com.panel.knowledge.application;

import java.util.List;

public record KbCatalogPage(List<KbCatalogItem> items, long total, int offset, int limit,
                            List<KbCatalogGroup> groups) {

    public KbCatalogPage(List<KbCatalogItem> items, long total, int offset, int limit) {
        this(items, total, offset, limit, List.of());
    }
}
