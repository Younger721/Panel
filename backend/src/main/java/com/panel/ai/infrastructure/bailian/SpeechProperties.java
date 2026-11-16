package com.panel.ai.infrastructure.bailian;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 百炼语音合成（Qwen-Audio-TTS 非实时接口）配置。
 *
 * <p>注意：TTS <b>不在</b> OpenAI 兼容通道（{@code /compatible-mode/v1}）里，
 * 走的是原生 {@code /api/v1/services/audio/tts/...}，所以这里单独一套 endpoint，
 * 不复用 {@link DashScopeProperties#getBaseUrl()}。</p>
 */
@ConfigurationProperties(prefix = "panel.ai.speech")
public class SpeechProperties {

    /** 总开关：关掉后所有语音接口直接返回 204，前端自动退回纯文字。 */
    private boolean enabled = true;

    /** 留空则复用 {@code panel.ai.dashscope.api-key}。 */
    private String apiKey = "";

    /**
     * 非实时合成端点。文档示例用的是业务空间专属域名
     * {@code https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/api/v1/services/audio/tts/SpeechSynthesizer}，
     * 但官方说明「现有域名仍可正常使用」，所以默认用公共域名，换专属域名只需改这里。
     */
    private String endpoint =
            "https://dashscope.aliyuncs.com/api/v1/services/audio/tts/SpeechSynthesizer";

    private String model = "qwen-audio-3.1-tts-flash";

    /** 默认音色：龙小夏，普通系统音色组（非 premium），稳重有权威感。 */
    private String voice = "longxiaoxia_v3.1";

    /** 已实测 mp3 可用，体积约为 wav 的 1/2.4；wav 亦可。 */
    private String format = "mp3";

    private int sampleRate = 24000;

    /** 默认语气指令；人设会覆盖它。 */
    private String instruction = "语气专业平稳，语速稍慢，吐字清晰";

    /** 单次请求的文本上限，超了直接截断（题目通常远小于此值）。 */
    private int maxChars = 800;

    private int timeoutSeconds = 45;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApiKey() {
        return apiKey == null ? "" : apiKey.trim();
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getVoice() {
        return voice;
    }

    public void setVoice(String voice) {
        this.voice = voice;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public void setSampleRate(int sampleRate) {
        this.sampleRate = sampleRate;
    }

    public String getInstruction() {
        return instruction;
    }

    public void setInstruction(String instruction) {
        this.instruction = instruction;
    }

    public int getMaxChars() {
        return maxChars;
    }

    public void setMaxChars(int maxChars) {
        this.maxChars = maxChars;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
