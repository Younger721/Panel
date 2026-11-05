package com.nova.ai.harness.application;

import com.nova.ai.domain.ContextSegment;
import com.nova.ai.application.ModelUsage;
import com.nova.ai.harness.capability.ToolResult;

import java.util.List;

public record AiToolOrchestrationResult(String directAnswer, List<ToolResult> toolResults,
                                        List<ContextSegment> context, ModelUsage usage, long latencyMs) {
    public AiToolOrchestrationResult(String directAnswer, List<ToolResult> toolResults,
                                     List<ContextSegment> context) {
        this(directAnswer, toolResults, context, null, 0);
    }

    public AiToolOrchestrationResult {
        toolResults = toolResults == null ? List.of() : List.copyOf(toolResults);
        context = context == null ? List.of() : List.copyOf(context);
    }
}
