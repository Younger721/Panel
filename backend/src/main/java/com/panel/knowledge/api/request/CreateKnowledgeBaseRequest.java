package com.panel.knowledge.api.request;

import com.panel.knowledge.application.CreateKnowledgeBaseCommand;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateKnowledgeBaseRequest(
        @NotBlank(message = "code 不能为空") @Size(max = 64, message = "code 不能超过 64 个字符") String code,
        @NotBlank(message = "名称不能为空") @Size(max = 128, message = "名称不能超过 128 个字符") String name,
        @Size(max = 512, message = "描述不能超过 512 个字符") String description,
        @NotBlank(message = "kind 不能为空") String kind,
        @NotBlank(message = "visibility 不能为空") String visibility,
        String chunkStrategy,
        @Min(value = 100, message = "chunkSize 最小 100") @Max(value = 2000, message = "chunkSize 最大 2000") Integer chunkSize,
        @Min(value = 0, message = "chunkOverlap 不能为负") Integer chunkOverlap,
        @Min(value = 1, message = "topK 最小 1") @Max(value = 30, message = "topK 最大 30") Integer topK,
        Boolean rerankEnabled,
        @DecimalMin(value = "0.0", message = "scoreThreshold 需在 0-1 之间")
        @DecimalMax(value = "1.0", message = "scoreThreshold 需在 0-1 之间") BigDecimal scoreThreshold) {

    public CreateKnowledgeBaseCommand toCommand() {
        return new CreateKnowledgeBaseCommand(code, name, description, kind, visibility, chunkStrategy,
                chunkSize, chunkOverlap, topK, rerankEnabled, scoreThreshold);
    }
}
