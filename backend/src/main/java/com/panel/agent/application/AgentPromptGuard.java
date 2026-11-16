package com.panel.agent.application;

import com.panel.shared.error.BusinessException;
import com.panel.shared.error.ErrorCode;

import java.util.List;
import java.util.Locale;

/**
 * 门店补充提示词的保存前校验（"事前兜底"）。
 * <p>这里不审内容，只拦两类明显越界的写法：超长、以及企图让模型忽略平台规则的指令。
 * 真正的安全边界在代码层（工具白名单、租户隔离、只读限制），提示词校验只是降低出错概率。
 */
public final class AgentPromptGuard {

    /** 与数据库列和前端输入框保持一致 */
    public static final int MAX_LENGTH = 4000;

    /** 明显试图覆盖平台规则的表述；命中即拒绝保存 */
    private static final List<String> FORBIDDEN_PHRASES = List.of(
            "忽略以上",
            "忽略以上所有",
            "忽略之前",
            "忽略前面",
            "无视规则",
            "无视平台",
            "忽略平台",
            "覆盖平台规则",
            "ignore previous",
            "ignore all previous",
            "ignore the above",
            "disregard the above",
            "disregard previous");

    private AgentPromptGuard() {
    }

    /**
     * 校验门店补充提示词；不合法时抛 {@link ErrorCode#VALIDATION_FAILED}。
     * <p>空字符串视为合法（平台应急清空后会处于该状态），但保存入口仍要求非空。
     */
    public static void validate(String prompt) {
        if (prompt == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (prompt.trim().length() > MAX_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "门店补充规则最多 " + MAX_LENGTH + " 个字");
        }
        String normalized = prompt.toLowerCase(Locale.ROOT);
        for (String phrase : FORBIDDEN_PHRASES) {
            if (normalized.contains(phrase)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "门店补充规则包含不允许的表述：" + phrase);
            }
        }
    }
}
