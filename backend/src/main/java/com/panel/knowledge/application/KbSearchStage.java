package com.panel.knowledge.application;

/**
 * 检索链路中的一个阶段，供前端实时点亮「思考链路」。
 *
 * <p>{@code status}：{@code RUNNING} / {@code COMPLETED} / {@code SKIPPED} / {@code FAILED}。
 * 同一 {@code id} 会先发 RUNNING，再发 COMPLETED（或 FAILED），前端按 id 覆盖。</p>
 */
public record KbSearchStage(
        String id,
        String label,
        String status,
        Integer count,
        Long durationMs,
        String detail) {
}
