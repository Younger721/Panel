package com.panel.knowledge.api.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 建一条知识点关系：默认 RELATED，权重 0.5。 */
public record CreateCategoryRelationRequest(
        @NotNull(message = "起点知识点不能为空") Long fromCategoryId,
        @NotNull(message = "终点知识点不能为空") Long toCategoryId,
        String relation,
        @Min(value = 0, message = "权重不能小于 0")
        @Max(value = 100, message = "权重不能大于 1")
        Double weight,
        String note) {
}
