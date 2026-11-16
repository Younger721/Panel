package com.panel.knowledge.application;

import java.time.Instant;
import java.util.List;

/** 题库题目视图：题干给列表，答案给详情。 */
public record KbQuestionView(
        Long id,
        Long kbId,
        Long documentId,
        Long chunkId,
        String stem,
        String answer,
        String difficulty,
        String company,
        String role,
        Integer year,
        List<String> tags,
        Instant createdAt) {
}
