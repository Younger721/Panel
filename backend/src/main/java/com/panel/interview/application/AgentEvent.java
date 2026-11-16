package com.panel.interview.application;

/** 面试编排的进度事件：让前端能显示"Planner 正在生成考察点"这类信息。 */
public record AgentEvent(String agent, String stage, Status status, String message,
                         Integer resultCount, long durationMs) {

    public enum Status {
        STARTED,
        COMPLETED,
        FAILED
    }

    public static AgentEvent started(String agent, String stage) {
        return new AgentEvent(agent, stage, Status.STARTED, null, null, 0);
    }

    public static AgentEvent finished(String agent, String stage, String message, Integer count, long startedAt) {
        return new AgentEvent(agent, stage, Status.COMPLETED, message, count,
                System.currentTimeMillis() - startedAt);
    }
}
