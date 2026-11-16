package com.panel.ai.application;

/**
 * 语音合成端口：把一段文本变成音频字节。
 *
 * <p>与 {@link ChatModelGateway} 平级——面试模块只依赖这个接口，<b>不认识百炼</b>。
 * 换供应商（或在没有额度时换成别的实现）不影响编排代码。</p>
 *
 * <p>契约：合成失败返回 {@code null}，<b>不抛异常</b>。语音是表现层增强，
 * 挂了应该静音继续面试，而不是把整场面试搞崩。</p>
 */
public interface SpeechSynthesisGateway {

    /**
     * @param text        要朗读的文本（调用方负责截断到模型上限）
     * @param voice       音色 ID，为空时用配置的默认音色
     * @param instruction 自然语言指令，例如「语气专业平稳，语速稍慢」；为空则不加
     * @return 音频字节；不可用或失败时返回 {@code null}
     */
    SpeechAudio synthesize(String text, String voice, String instruction);

    record SpeechAudio(byte[] content, String mimeType, String model, String voice) {
    }
}
