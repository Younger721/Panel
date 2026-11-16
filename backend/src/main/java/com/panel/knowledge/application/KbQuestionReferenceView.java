package com.panel.knowledge.application;

import java.util.List;

/** 题目 → 技术文档证据：双库联动的读侧视图。 */
public record KbQuestionReferenceView(
        Long chunkId,
        Long kbId,
        Long documentId,
        String documentName,
        String heading,
        String text,
        List<String> concepts) {
}
