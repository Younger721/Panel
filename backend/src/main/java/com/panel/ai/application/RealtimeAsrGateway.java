package com.panel.ai.application;

import java.util.List;

/**
 * 实时语音识别端口（流式）。
 *
 * <p>契约：{@link #open} <b>不抛异常</b>。没配 key、上游连不上、任务被拒，
 * 一律通过 {@link Listener#onFailed} 通知调用方，拿到的是一个「空会话」，
 * 调用方只管关掉它即可。这个约定和 {@link SpeechSynthesisGateway} 的「失败返回 null」一致，
 * 目的是不让语音链路的抖动升级成 500。</p>
 *
 * <p>线程模型：回调可能在 IO 线程上触发，实现方需保证回调顺序，
 * 调用方（WebSocket Handler）负责把结果转给浏览器。</p>
 */
public interface RealtimeAsrGateway {

    /**
     * 开一个识别任务。
     *
     * @param options 模型、热词、回调监听器
     * @return 会话句柄；即便失败也会返回一个可安全 close 的句柄
     */
    Session open(OpenOptions options);

    /** 一次识别任务（对应百炼的 run-task → finish-task）。 */
    interface Session {

        /** 推一帧 16kHz / 16bit / 单声道 PCM。队列满等异常通过 onFailed 上报，本方法不抛。 */
        void send(byte[] pcm);

        /** 说完了：发 finish-task，等上游吐出最后一句。 */
        void finish();

        /** 立即丢弃（Esc / 切题 / 断连兜底）。 */
        void close();
    }

    interface Listener {

        /** 上游任务已就绪，可以开始推音频了。 */
        void onStarted();

        /** 中间结果：同一 sentenceId 会反复修订，调用方应「覆盖」而不是「追加」。 */
        void onPartial(int sentenceId, String text);

        /** 句子定稿：追加到草稿。 */
        void onSentence(int sentenceId, String text, int beginMs, int endMs);

        /** 任务结束，带累计用量。 */
        void onFinished(Usage usage);

        /** 失败。code 是给前端看的稳定枚举，message 只用于日志/提示。 */
        void onFailed(String code, String message);
    }

    record OpenOptions(String model, List<String> vocabulary, List<String> languageHints, Listener listener) {
    }

    /** 百炼返回的是累计值。 */
    record Usage(int durationSeconds, int inputTokens, int outputTokens, int totalTokens) {

        public static Usage empty() {
            return new Usage(0, 0, 0, 0);
        }

        public int tokens() {
            return totalTokens > 0 ? totalTokens : inputTokens + outputTokens;
        }
    }
}
