package com.panel.knowledge.api.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record KbChatRequest(
        Long sessionId,
        @NotBlank(message = "问题不能为空")
        @Size(max = 2000, message = "问题最多 2000 个字符") String question,
        @Min(value = 1, message = "topK 至少为 1") @Max(value = 20, message = "topK 最多为 20") Integer topK,
        String mode,
        Boolean rerank,
        Boolean publishedOnly) {
}
