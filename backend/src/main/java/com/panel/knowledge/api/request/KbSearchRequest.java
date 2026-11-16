package com.panel.knowledge.api.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/** 检索请求：题库可按公司/难度/年份/标签做前置过滤，技术文档忽略这些字段；categoryIds 为目录子树过滤。 */
public record KbSearchRequest(
        @NotBlank(message = "检索词不能为空") String query,
        @Min(value = 1, message = "topK 至少为 1") @Max(value = 20, message = "topK 最多为 20") Integer topK,
        String mode,
        Boolean rerank,
        Boolean publishedOnly,
        String company,
        String difficulty,
        Integer year,
        List<String> tags,
        List<Long> categoryIds) {
}
