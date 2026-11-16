package com.panel.knowledge.application;

import java.util.List;

public record KbQuestionPage(List<KbQuestionView> items, long total) {
}
