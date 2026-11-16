package com.panel.knowledge.application;

import java.util.List;

/** 从题库 md 里拆出来的一道题（尚未落库）。 */
public record KbQuestionDraft(
        Long chunkId,
        String stem,
        String answer,
        String difficulty,
        String company,
        String role,
        Integer year,
        List<String> tags) {
}
