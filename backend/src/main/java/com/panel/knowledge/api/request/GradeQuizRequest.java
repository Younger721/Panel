package com.panel.knowledge.api.request;

import jakarta.validation.constraints.NotBlank;

/** 自评结果：CORRECT / WRONG / UNSURE / SKIPPED。 */
public record GradeQuizRequest(@NotBlank(message = "自评结果不能为空") String result) {
}
