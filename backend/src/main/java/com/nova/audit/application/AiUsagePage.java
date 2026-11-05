package com.nova.audit.application;

import com.nova.ai.application.AiUsageRecord;

import java.util.List;

public record AiUsagePage(List<AiUsageRecord> content, int page, int size, long totalElements,
                          AiUsageSummary summary) {
}
