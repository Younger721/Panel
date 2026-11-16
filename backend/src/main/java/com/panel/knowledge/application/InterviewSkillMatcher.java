package com.panel.knowledge.application;

import com.panel.knowledge.domain.model.KbCategory;

import java.util.List;

/**
 * 面试轮次的 {@code skill}（自由文本，例如"自我介绍""技术栈堆叠无深度标注"）
 * 到知识目录节点的映射。
 *
 * <p>只用"名字对齐"，不让模型凭空造节点：解析不出来就返回 null，交给人工补——
 * 目录是产品结构，被模型写脏了要花十倍力气收拾。错挂比不挂更糟：
 * 答错时给的知识点会指到错的地方，学员会以为系统在胡说。</p>
 *
 * <p>匹配顺序：归一化后完全相同（同名的取更浅的那个）→ 互相包含（取最长的节点名）→ 放弃。</p>
 */
final class InterviewSkillMatcher {

    private InterviewSkillMatcher() {
    }

    static KbCategory match(String skill, List<KbCategory> nodes) {
        String target = normalize(skill);
        if (target.isEmpty() || nodes == null || nodes.isEmpty()) {
            return null;
        }
        KbCategory exact = null;
        KbCategory partial = null;
        int partialLength = 0;
        for (KbCategory node : nodes) {
            String name = normalize(node.name());
            if (name.isEmpty()) {
                continue;
            }
            if (name.equals(target)) {
                if (exact == null || node.depth() < exact.depth()) {
                    exact = node;
                }
                continue;
            }
            if ((target.contains(name) || name.contains(target)) && name.length() > partialLength) {
                partialLength = name.length();
                partial = node;
            }
        }
        return exact != null ? exact : partial;
    }

    /** 去掉空白与常见标点，统一全角/半角与大小写，只比较"内容"。 */
    static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(raw.length());
        for (char ch : raw.toCharArray()) {
            if (Character.isWhitespace(ch)) {
                continue;
            }
            char normalized = switch (ch) {
                case '（' -> '(';
                case '）' -> ')';
                case '，' -> ',';
                case '：' -> ':';
                case '、' -> '/';
                case '／' -> '/';
                case '－', '—', '–' -> '-';
                default -> ch;
            };
            if ("·•.。;；\"'“”‘’「」《》".indexOf(normalized) >= 0) {
                continue;
            }
            builder.append(normalized);
        }
        return builder.toString().toLowerCase(java.util.Locale.ROOT);
    }
}
