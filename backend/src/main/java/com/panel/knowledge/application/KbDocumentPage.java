package com.panel.knowledge.application;

import java.util.List;

public record KbDocumentPage(List<KbDocumentView> items, long total, int page, int size) {
}
