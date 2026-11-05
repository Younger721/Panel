package com.nova.interview.application;

/**
 * 编排进度回调。
 *
 * <p>同步接口传 {@link #NOOP}，SSE 接口传一个把事件写给 {@code SseEmitter} 的实现——
 * 编排逻辑只写一份，不需要为流式再复制一套流程。</p>
 */
@FunctionalInterface
public interface InterviewProgressListener {

    InterviewProgressListener NOOP = (event, payload) -> {
    };

    void onEvent(String event, Object payload);
}
