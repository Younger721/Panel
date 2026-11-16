package com.panel.ai.infrastructure.bailian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.panel.ai.application.RealtimeAsrGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 百炼实时语音识别适配器（Qwen-Audio-ASR-Message）。
 *
 * <p>协议是 run-task / continue-task / finish-task 三段式，音频以二进制帧
 * 直接推到同一条 WebSocket 上。用 JDK 自带的 {@link HttpClient#newWebSocketBuilder()}
 * 实现，不额外引 Netty / WebFlux。</p>
 *
 * <p>两个刻意的选择：</p>
 * <ul>
 *   <li><b>单独一条发送线程</b>（{@code pump}）：JDK WebSocket 不允许并发 send，
 *       而音频每 100ms 一帧，用阻塞发一帧等一帧最简单，顺带获得天然的背压。</li>
 *   <li><b>收到 task-started 之前不转发音频</b>：否则首批帧会被上游丢掉，
 *       表现就是「开头一两个字丢了」。</li>
 * </ul>
 */
public class BailianRealtimeAsrAdapter implements RealtimeAsrGateway {

    private static final Logger log = LoggerFactory.getLogger(BailianRealtimeAsrAdapter.class);

    private final AsrProperties properties;
    private final String apiKey;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public BailianRealtimeAsrAdapter(AsrProperties properties, String dashScopeApiKey, ObjectMapper mapper) {
        this.properties = properties;
        this.apiKey = properties.getApiKey().isBlank()
                ? (dashScopeApiKey == null ? "" : dashScopeApiKey.trim())
                : properties.getApiKey();
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public Session open(OpenOptions options) {
        Task task = new Task(options);
        if (!properties.isEnabled()) {
            task.fail("DISABLED", "realtime asr disabled");
            return task;
        }
        if (apiKey.isBlank()) {
            task.fail("NOT_CONFIGURED", "missing dashscope api key");
            return task;
        }
        task.start();
        return task;
    }

    /** 一次识别任务。同时充当 WebSocket 监听器。 */
    private final class Task implements Session, WebSocket.Listener {

        private final OpenOptions options;
        private final String taskId = UUID.randomUUID().toString();
        private final BlockingQueue<byte[]> queue =
                new LinkedBlockingQueue<>(Math.max(4, properties.getFrameQueueSize()));
        private final CountDownLatch startedLatch = new CountDownLatch(1);
        private final CountDownLatch finishedLatch = new CountDownLatch(1);
        private final AtomicBoolean failed = new AtomicBoolean(false);
        private final AtomicBoolean finishRequested = new AtomicBoolean(false);
        private final Object sendLock = new Object();
        private final StringBuilder textBuffer = new StringBuilder();

        private volatile WebSocket socket;
        private volatile Thread pump;
        private volatile boolean closed;
        /**
         * 百炼把累计用量放在 {@code result-generated} 里，{@code task-finished} 可能不带。
         * 所以每个 result 都更新一次，收尾时兜底用。
         */
        private volatile Usage latestUsage = Usage.empty();

        Task(OpenOptions options) {
            this.options = options;
        }

        void start() {
            String model = properties.getModel();
            log.debug("asr open task={} model={}", taskId, model);
            http.newWebSocketBuilder()
                    .header("Authorization", "Bearer " + apiKey)
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(properties.getWsUrl()), this)
                    .whenComplete((ws, error) -> {
                        if (error != null) {
                            fail("CONNECT_FAILED", String.valueOf(error.getMessage()));
                        }
                    });
        }

        // ---------- 会话 API ----------

        @Override
        public void send(byte[] pcm) {
            if (pcm == null || pcm.length == 0 || closed || finishRequested.get()) {
                return;
            }
            if (!queue.offer(pcm)) {
                // 队列满说明客户端网络已经跟不上；丢帧等于毁掉这次回答，只能断开重说
                fail("BACKPRESSURE", "client cannot keep up");
            }
        }

        @Override
        public void finish() {
            if (closed || !finishRequested.compareAndSet(false, true)) {
                return;
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            WebSocket ws = socket;
            if (ws != null) {
                try {
                    ws.abort();
                } catch (RuntimeException ignored) {
                    // 已经关了/正在关：abort 可能抛，清理流程不能被它打断
                }
            }
            Thread worker = pump;
            if (worker != null) {
                worker.interrupt();
            }
        }

        // ---------- WebSocket.Listener ----------

        @Override
        public void onOpen(WebSocket webSocket) {
            this.socket = webSocket;
            try {
                sendText(runTaskPayload());
            } catch (Exception e) {
                fail("RUN_TASK_FAILED", String.valueOf(e.getMessage()));
                return;
            }
            pump = new Thread(this::pumpLoop, "asr-pump-" + taskId.substring(0, 8));
            pump.setDaemon(true);
            pump.start();
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                String text = textBuffer.toString();
                textBuffer.setLength(0);
                try {
                    handleEvent(mapper.readTree(text));
                } catch (Exception e) {
                    log.warn("asr parse failed task={}: {}", taskId, e.getMessage());
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            fail("UPSTREAM_ERROR", String.valueOf(error.getMessage()));
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (!closed && finishedLatch.getCount() > 0) {
                fail("UPSTREAM_CLOSED", "code=" + statusCode + " reason=" + reason);
            }
            return null;
        }

        // ---------- 内部流程 ----------

        private void pumpLoop() {
            try {
                if (!startedLatch.await(properties.getIdleTimeoutSeconds(), TimeUnit.SECONDS)) {
                    fail("IDLE_TIMEOUT", "no audio received after connect");
                    return;
                }
                while (!closed) {
                    byte[] frame = queue.poll(200, TimeUnit.MILLISECONDS);
                    if (frame != null) {
                        sendBinary(frame);
                        continue;
                    }
                    // 松手了且缓冲已排空 → 收尾
                    if (finishRequested.get() && queue.isEmpty()) {
                        break;
                    }
                }
                if (closed) {
                    return;
                }
                sendText(finishTaskPayload());
                if (!finishedLatch.await(15, TimeUnit.SECONDS) && !closed) {
                    fail("UPSTREAM_TIMEOUT", "no task-finished after finish-task");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                fail("SEND_FAILED", String.valueOf(e.getMessage()));
            }
        }

        private void handleEvent(JsonNode root) {
            String event = root.path("header").path("event").asText("");
            switch (event) {
                case "task-started" -> {
                    if (!closed) {
                        startedLatch.countDown();
                        notifySafe(options.listener()::onStarted);
                    }
                }
                case "result-generated" -> handleResult(root.path("payload"));
                case "task-finished" -> {
                    Usage usage = usageOf(root.path("payload").path("usage"));
                    if (usage.tokens() <= 0) {
                        usage = latestUsage;
                    }
                    Usage reported = usage;
                    notifySafe(() -> options.listener().onFinished(reported));
                    finishedLatch.countDown();
                    WebSocket ws = socket;
                    if (ws != null) {
                        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
                    }
                    close();
                }
                case "task-failed" -> fail(
                        mapError(root.path("header").path("error_code").asText(""),
                                root.path("header").path("error_message").asText("")),
                        root.path("header").path("error_message").asText(""));
                default -> {
                    // 未知事件忽略，避免新事件类型把链路打断。
                    // 但要留一条 debug：上游协议一变（事件改名、加了预检事件），
                    // 这里静默就会变成"连接成功、任务永不 started、最终 IDLE_TIMEOUT"，
                    // 现场什么都看不到（实测就是这么排查的）。
                    log.debug("asr task={} unknown event={}", taskId, event);
                }
            }
        }

        private void handleResult(JsonNode payload) {
            JsonNode usageNode = payload.path("usage");
            if (!usageNode.isMissingNode() && !usageNode.isEmpty()) {
                latestUsage = usageOf(usageNode);
            }
            JsonNode sentence = payload.path("output").path("sentence");
            if (sentence.isMissingNode() || sentence.isEmpty()) {
                return;
            }
            if (sentence.path("heartbeat").asBoolean(false)) {
                return;
            }
            int sentenceId = sentence.path("sentence_id").asInt(0);
            String text = sentence.path("text").asText("");
            boolean end = sentence.path("sentence_end").asBoolean(false);
            boolean begin = sentence.path("sentence_begin").asBoolean(false);
            // 句子开始事件可能带空文本，前端此时还不需要渲染
            if (text.isEmpty() || closed) {
                return;
            }
            if (begin && !end) {
                return;
            }
            if (end) {
                int beginMs = sentence.path("begin_time").asInt(0);
                int endMs = sentence.path("end_time").asInt(0);
                notifySafe(() -> options.listener().onSentence(sentenceId, text, beginMs, endMs));
            } else {
                notifySafe(() -> options.listener().onPartial(sentenceId, text));
            }
        }

        private Usage usageOf(JsonNode usage) {
            if (usage.isMissingNode() || usage.isEmpty()) {
                return Usage.empty();
            }
            return new Usage(
                    usage.path("duration").asInt(0),
                    usage.path("input_tokens").asInt(0),
                    usage.path("output_tokens").asInt(0),
                    usage.path("total_tokens").asInt(0));
        }

        private String mapError(String code, String message) {
            String normalized = (code == null ? "" : code).toLowerCase();
            if (normalized.contains("internalservererror")) {
                return "UPSTREAM_ERROR";
            }
            if (normalized.contains("invalidapikey") || normalized.contains("unauthorized")
                    || normalized.contains("authorization")) {
                return "UPSTREAM_AUTH";
            }
            if (normalized.contains("quota") || normalized.contains("freetieronly")
                    || message != null && message.toLowerCase().contains("freetieronly")) {
                return "QUOTA_EXHAUSTED";
            }
            if (normalized.contains("throttl") || normalized.contains("ratelimit")) {
                return "UPSTREAM_THROTTLED";
            }
            return "UPSTREAM_CLIENT";
        }

        private void fail(String code, String message) {
            if (!failed.compareAndSet(false, true)) {
                return;
            }
            log.warn("asr task={} failed code={} msg={}", taskId, code, message);
            notifySafe(() -> options.listener().onFailed(code, message));
            finishedLatch.countDown();
            close();
        }

        private void notifySafe(Runnable action) {
            try {
                action.run();
            } catch (RuntimeException e) {
                log.warn("asr listener error task={}: {}", taskId, e.getMessage());
            }
        }

        private void sendBinary(byte[] frame) throws Exception {
            WebSocket ws = socket;
            if (ws == null) {
                throw new IllegalStateException("socket not ready");
            }
            synchronized (sendLock) {
                ws.sendBinary(ByteBuffer.wrap(frame), true).get(10, TimeUnit.SECONDS);
            }
        }

        private void sendText(String json) throws Exception {
            WebSocket ws = socket;
            if (ws == null) {
                throw new IllegalStateException("socket not ready");
            }
            synchronized (sendLock) {
                ws.sendText(json, true).get(10, TimeUnit.SECONDS);
            }
        }

        // ---------- 报文构造 ----------

        private String runTaskPayload() throws Exception {
            ObjectNode root = mapper.createObjectNode();
            ObjectNode header = root.putObject("header");
            header.put("action", "run-task");
            header.put("task_id", taskId);
            header.put("streaming", "duplex");

            ObjectNode payload = root.putObject("payload");
            payload.put("task_group", "audio");
            payload.put("task", "asr");
            payload.put("function", "recognition");
            payload.put("model", properties.getModel());

            ObjectNode params = payload.putObject("parameters");
            params.put("format", properties.getFormat());
            params.put("sample_rate", properties.getSampleRate());
            // 这个开关默认是 false，不开就没有中间结果
            params.put("intermediate_result_enabled", properties.isIntermediateResults());
            params.put("disfluency_removal_enabled", properties.isDisfluencyRemoval());
            params.put("heartbeat", properties.isHeartbeat());
            params.put("vad_model", properties.getVadModel());
            params.put("max_sentence_silence", properties.getMaxSentenceSilenceMs());

            List<String> hints = options.languageHints() != null && !options.languageHints().isEmpty()
                    ? options.languageHints() : properties.getLanguageHints();
            if (hints != null && !hints.isEmpty()) {
                ArrayNode array = params.putArray("language_hints");
                hints.forEach(array::add);
            }
            List<String> vocabulary = options.vocabulary() != null && !options.vocabulary().isEmpty()
                    ? options.vocabulary() : properties.getHotwords();
            if (vocabulary != null && !vocabulary.isEmpty()) {
                ObjectNode words = params.putObject("vocabulary");
                vocabulary.stream()
                        .filter(word -> word != null && !word.isBlank())
                        .forEach(word -> words.put(word.trim(), 5));
            }
            // 不带上下文时传空对象
            payload.putObject("input");
            return mapper.writeValueAsString(root);
        }

        private String finishTaskPayload() throws Exception {
            ObjectNode root = mapper.createObjectNode();
            ObjectNode header = root.putObject("header");
            header.put("action", "finish-task");
            header.put("task_id", taskId);
            header.put("streaming", "duplex");
            root.putObject("payload").putObject("input");
            return mapper.writeValueAsString(root);
        }
    }
}
