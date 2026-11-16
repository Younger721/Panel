package com.panel.audit.application;

import com.panel.ai.application.AiUsageRecord;

import java.util.List;

public record AiUsagePage(List<AiUsageRecord> content, int page, int size, long totalElements,
                          AiUsageSummary summary) {
}
