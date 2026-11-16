package com.panel.knowledge.api.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** 开一组题：size 缺省 5，最多 20。 */
public record StartQuizRequest(
        @Min(value = 1, message = "至少抽 1 道题")
        @Max(value = 20, message = "一次最多 20 道题")
        Integer size) {
}
