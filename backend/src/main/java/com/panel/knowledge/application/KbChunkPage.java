package com.panel.knowledge.application;

import java.util.List;

public record KbChunkPage(List<KbChunkView> items, long total, int page, int size) {
}
