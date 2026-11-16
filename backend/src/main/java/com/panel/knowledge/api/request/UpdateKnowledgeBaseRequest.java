package com.panel.knowledge.api.request;

import com.panel.knowledge.application.UpdateKnowledgeBaseCommand;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** 部分更新：不传的字段保持原值。 */
public record UpdateKnowledgeBaseRequest(
        @Size(max = 128, message = "名称不能超过 128 个字符") String name,
        @Size(max = 512, message = "描述不能超过 512 个字符") String description,
        String visibility,
        String chunkStrategy,
        @Min(value = 100, message = "chunkSize 最小 100") @Max(value = 2000, message = "chunkSize 最大 2000") Integer chunkSize,
        @Min(value = 0, message = "chunkOverlap 不能为负") Integer chunkOverlap,
        @Min(value = 1, message = "topK 最小 1") @Max(value = 30, message = "topK 最大 30") Integer topK,
        Boolean rerankEnabled,
        @DecimalMin(value = "0.0", message = "scoreThreshold 需在 0-1 之间")
        @DecimalMax(value = "1.0", message = "scoreThreshold 需在 0-1 之间") BigDecimal scoreThreshold) {

    public UpdateKnowledgeBaseCommand toCommand() {
        return new UpdateKnowledgeBaseCommand(name, description, visibility, chunkStrategy,
                chunkSize, chunkOverlap, topK, rerankEnabled, scoreThreshold);
    }
}
