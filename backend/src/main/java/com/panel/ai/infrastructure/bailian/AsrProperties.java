package com.panel.ai.infrastructure.bailian;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 百炼实时语音识别（Qwen-Audio-ASR-Message）配置。
 *
 * <p>和 TTS 一样不在 OpenAI 兼容通道里，走的是 WebSocket 原生协议
 * （run-task / continue-task / finish-task），所以单独一套 endpoint。</p>
 *
 * <p>实测数据（2026-11-08，见设计文档 §16）：首字约开流后 450ms，
 * 中间结果 2.4 次/秒，token 密度 14.6/秒。</p>
 */
@ConfigurationProperties(prefix = "panel.ai.asr")
public class AsrProperties {

    /** 总开关：关掉后前端拿不到 ticket，自动退回纯键盘。 */
    private boolean enabled = true;

    /** 留空则复用 {@code panel.ai.dashscope.api-key}。 */
    private String apiKey = "";

    /** 百炼实时识别 WebSocket 端点。 */
    private String wsUrl = "wss://dashscope.aliyuncs.com/api-ws/v1/inference";

    /**
     * 默认 -message：面向语音消息/输入法这类短段输入，首字最快、上屏最密。
     * 同一套协议也支持 qwen-audio-3.1-asr-flash-streaming（长时转写用）。
     */
    private String model = "qwen-audio-3.1-asr-flash-message";

    /** 实时接口只支持 16000，填别的会直接任务失败。 */
    private int sampleRate = 16000;

    private String format = "pcm";

    /** 笔记本麦克风是近场；远场模型（far_field_meeting_16k）断句偏保守。 */
    private String vadModel = "near_meeting_16k";

    /** 默认 800ms 会把正常停顿切成碎句，调到 1200ms。 */
    private int maxSentenceSilenceMs = 1200;

    /**
     * 中间结果开关。百炼默认 false——不开就只有句子结束才出字，
     * 「边讲边看」直接失效。这是最容易漏的一个参数。
     */
    private boolean intermediateResults = true;

    /** 原生润色：去语气词、自我纠正。 */
    private boolean disfluencyRemoval = true;

    /** 长时间不发声时靠心跳保活。 */
    private boolean heartbeat = true;

    private List<String> languageHints = new ArrayList<>(List.of("zh", "en"));

    /** 即时热词，提升专有名词识别率。 */
    private List<String> hotwords = new ArrayList<>();

    /** 单次说话硬上限（秒）。 */
    private int maxSessionSeconds = 90;

    /** 建连后多久没收到音频就断开，防止白占连接。 */
    private int idleTimeoutSeconds = 5;

    /** 上行帧队列长度（每帧 100ms）。 */
    private int frameQueueSize = 50;

    /** 每个用户每天的识别次数上限。 */
    private int dailyQuotaPerUser = 400;

    /** 同时只允许一条连接，避免一个人开多路刷并发。 */
    private int maxConnectionsPerUser = 1;

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

    public String getWsUrl() {
        return wsUrl;
    }

    public void setWsUrl(String wsUrl) {
        this.wsUrl = wsUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public void setSampleRate(int sampleRate) {
        this.sampleRate = sampleRate;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public String getVadModel() {
        return vadModel;
    }

    public void setVadModel(String vadModel) {
        this.vadModel = vadModel;
    }

    public int getMaxSentenceSilenceMs() {
        return maxSentenceSilenceMs;
    }

    public void setMaxSentenceSilenceMs(int maxSentenceSilenceMs) {
        this.maxSentenceSilenceMs = maxSentenceSilenceMs;
    }

    public boolean isIntermediateResults() {
        return intermediateResults;
    }

    public void setIntermediateResults(boolean intermediateResults) {
        this.intermediateResults = intermediateResults;
    }

    public boolean isDisfluencyRemoval() {
        return disfluencyRemoval;
    }

    public void setDisfluencyRemoval(boolean disfluencyRemoval) {
        this.disfluencyRemoval = disfluencyRemoval;
    }

    public boolean isHeartbeat() {
        return heartbeat;
    }

    public void setHeartbeat(boolean heartbeat) {
        this.heartbeat = heartbeat;
    }

    public List<String> getLanguageHints() {
        return languageHints;
    }

    public void setLanguageHints(List<String> languageHints) {
        this.languageHints = languageHints == null ? new ArrayList<>() : languageHints;
    }

    public List<String> getHotwords() {
        return hotwords;
    }

    public void setHotwords(List<String> hotwords) {
        this.hotwords = hotwords == null ? new ArrayList<>() : hotwords;
    }

    public int getMaxSessionSeconds() {
        return maxSessionSeconds;
    }

    public void setMaxSessionSeconds(int maxSessionSeconds) {
        this.maxSessionSeconds = maxSessionSeconds;
    }

    public int getIdleTimeoutSeconds() {
        return idleTimeoutSeconds;
    }

    public void setIdleTimeoutSeconds(int idleTimeoutSeconds) {
        this.idleTimeoutSeconds = idleTimeoutSeconds;
    }

    public int getFrameQueueSize() {
        return frameQueueSize;
    }

    public void setFrameQueueSize(int frameQueueSize) {
        this.frameQueueSize = frameQueueSize;
    }

    public int getDailyQuotaPerUser() {
        return dailyQuotaPerUser;
    }

    public void setDailyQuotaPerUser(int dailyQuotaPerUser) {
        this.dailyQuotaPerUser = dailyQuotaPerUser;
    }

    public int getMaxConnectionsPerUser() {
        return maxConnectionsPerUser;
    }

    public void setMaxConnectionsPerUser(int maxConnectionsPerUser) {
        this.maxConnectionsPerUser = maxConnectionsPerUser;
    }
}
