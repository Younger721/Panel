package com.panel.interview.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.panel.ai.application.AiUsageCommand;
import com.panel.ai.application.DefaultAiUsageService;
import com.panel.ai.application.RealtimeAsrGateway;
import com.panel.interview.application.AsrQuotaService;
import com.panel.interview.application.AsrTicketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 浏览器 ↔ 后端的语音通道。
 *
 * <p>一条连接 = 一次「按住说话」。前端在 keydown 时取 ticket 建连，收到 final 后连接即关闭，
 * 这样没有跨句的状态要维护，也不会出现「上一次的尾巴串到这一次」。</p>
 *
 * <p>下行帧见设计文档 §8.2；上行只有两种：二进制 PCM 帧，以及 {@code {"type":"finish"}} /
 * {@code {"type":"cancel"}} 两个控制帧。</p>
 */
@Component
public class AsrWebSocketHandler extends AbstractWebSocketHandler {

    public static final String ATTR_TICKET = "panel.asr.ticket";

    private static final Logger log = LoggerFactory.getLogger(AsrWebSocketHandler.class);
    private static final String FEATURE = "INTERVIEW_ASR";
    private static final String PROVIDER = "dashscope";

    private final RealtimeAsrGateway gateway;
    private final DefaultAiUsageService usageRecorder;
    private final AsrQuotaService quota;
    private final ObjectMapper mapper;
    private final int maxSessionSeconds;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "asr-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    public AsrWebSocketHandler(RealtimeAsrGateway gateway,
                               DefaultAiUsageService usageRecorder,
                               AsrQuotaService quota,
                               ObjectMapper mapper,
                               @Value("${panel.ai.asr.max-session-seconds:90}") int maxSessionSeconds) {
        this.gateway = gateway;
        this.usageRecorder = usageRecorder;
        this.quota = quota;
        this.mapper = mapper;
        this.maxSessionSeconds = maxSessionSeconds;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Object attribute = session.getAttributes().get(ATTR_TICKET);
        if (!(attribute instanceof AsrTicketService.Ticket ticket)) {
            closeQuietly(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        WebSocketSession client = new ConcurrentWebSocketSessionDecorator(session, 5_000, 512 * 1024);
        Connection connection = new Connection(session.getId(), client, ticket);
        connections.put(session.getId(), connection);
        connection.start();
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        Connection connection = connections.get(session.getId());
        if (connection == null) {
            return;
        }
        ByteBuffer buffer = message.getPayload();
        byte[] pcm = new byte[buffer.remaining()];
        buffer.get(pcm);
        connection.sendAudio(pcm);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Connection connection = connections.get(session.getId());
        if (connection == null) {
            return;
        }
        String type;
        try {
            JsonNode node = mapper.readTree(message.getPayload());
            type = node.path("type").asText("");
        } catch (Exception e) {
            type = "finish";
        }
        switch (type) {
            case "finish" -> connection.finish();
            case "cancel" -> connection.cancel();
            default -> {
                // 未知控制帧忽略
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Connection connection = connections.get(session.getId());
        if (connection != null) {
            connection.cleanup();
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("asr transport error session={}: {}", session.getId(), exception.getMessage());
        Connection connection = connections.get(session.getId());
        if (connection != null) {
            connection.cleanup();
        }
    }

    // ------------------------------------------------------------------

    private final class Connection {

        private final String id;
        private final WebSocketSession client;
        private final AsrTicketService.Ticket ticket;
        private final long startedAt = System.currentTimeMillis();
        private final AtomicBoolean released = new AtomicBoolean(false);
        /**
         * 上游建连（拿到 task-started）之前到达的音频先攒着。
         * 前端 WS 一打开就开始推帧，而百炼那边还要握手 + run-task，
         * 不缓冲就会把「第一个字」丢掉。
         */
        private final ArrayDeque<byte[]> pending = new ArrayDeque<>();

        private volatile RealtimeAsrGateway.Session upstream;
        private volatile boolean upstreamReady;
        /**
         * 上游握手期间用户就说完了。
         *
         * <p>这时把 finish 直接发给上游会被丢掉（它还在 run-task），用户永远等不到 final，
         * 前端 6 秒后报「识别超时」，这一句白说了、配额也已经扣了。
         * 记下来，等 {@code onStarted} 把缓冲的音频冲完再补发。</p>
         */
        private volatile boolean finishRequested;
        private volatile ScheduledFuture<?> watchdog;

        Connection(String id, WebSocketSession client, AsrTicketService.Ticket ticket) {
            this.id = id;
            this.client = client;
            this.ticket = ticket;
        }

        void start() {
            upstream = gateway.open(new RealtimeAsrGateway.OpenOptions(
                    ticket.model(), null, null, new Listener()));
            if (released.get()) {
                // 建连阶段就已经失败并清理过了
                upstream.close();
                return;
            }
            watchdog = scheduler.schedule(this::onTimeLimit, maxSessionSeconds, TimeUnit.SECONDS);
        }

        void sendAudio(byte[] pcm) {
            RealtimeAsrGateway.Session session = upstream;
            if (session != null && upstreamReady) {
                session.send(pcm);
                return;
            }
            synchronized (pending) {
                // 上限 30 帧（3 秒）：再多说明上游一直没起来，没必要继续攒
                if (!released.get() && pending.size() < 30) {
                    pending.addLast(pcm);
                }
            }
        }

        private void drainPending() {
            List<byte[]> buffered;
            synchronized (pending) {
                buffered = new ArrayList<>(pending);
                pending.clear();
            }
            RealtimeAsrGateway.Session session = upstream;
            if (session == null) {
                return;
            }
            upstreamReady = true;
            buffered.forEach(session::send);
            // 握手期间到达的 finish 现在才有效：先送完音频再说"我说完了"，
            // 顺序反了上游会当成空音频
            if (finishRequested) {
                finishRequested = false;
                session.finish();
            }
        }

        void finish() {
            if (!upstreamReady) {
                // 上游还没起来：记下来，onStarted 之后再补发
                finishRequested = true;
                return;
            }
            RealtimeAsrGateway.Session session = upstream;
            if (session != null) {
                session.finish();
            }
        }

        void cancel() {
            RealtimeAsrGateway.Session session = upstream;
            if (session != null) {
                session.close();
            }
            finishAndClose();
        }

        private void onTimeLimit() {
            log.info("asr session {} hit {}s limit, forcing finish", id, maxSessionSeconds);
            finish();
        }

        private void recordUsage(RealtimeAsrGateway.Usage usage) {
            if (usage == null || usage.tokens() <= 0) {
                return;
            }
            // 平台管理员没有租户，而 JpaAiUsageRepository 要求 tenant_id 非空。
            // 单租户形态下这是已知缺口（整个 ai_usage_record 一直是空的），
            // 这里只记日志，不能因为记账失败影响识别。
            if (ticket.actor().tenantId() == null) {
                log.info("asr usage not persisted (platform actor) session={} tokens={} duration={}s",
                        id, usage.tokens(), usage.durationSeconds());
                return;
            }
            try {
                usageRecorder.record(ticket.actor(), new AiUsageCommand(
                        ticket.model(),
                        usage.inputTokens(),
                        usage.outputTokens(),
                        usage.totalTokens(),
                        System.currentTimeMillis() - startedAt,
                        null,
                        "SUCCESS",
                        null,
                        PROVIDER,
                        FEATURE,
                        "PROVIDER",
                        null,
                        null));
            } catch (RuntimeException e) {
                // 记账失败不该影响用户已经把话说完了这件事
                log.warn("asr usage record failed session={}: {}", id, e.getMessage());
            }
        }

        private void finishAndClose() {
            try {
                if (client.isOpen()) {
                    client.close(CloseStatus.NORMAL);
                }
            } catch (Exception e) {
                log.debug("asr close client failed session={}: {}", id, e.getMessage());
            }
            cleanup();
        }

        void cleanup() {
            if (!released.compareAndSet(false, true)) {
                return;
            }
            ScheduledFuture<?> timer = watchdog;
            if (timer != null) {
                timer.cancel(false);
            }
            RealtimeAsrGateway.Session session = upstream;
            if (session != null) {
                session.close();
            }
            synchronized (pending) {
                pending.clear();
            }
            connections.remove(id);
            quota.releaseConnection(ticket.userId());
        }

        private void send(ObjectNode node) {
            try {
                if (client.isOpen()) {
                    client.sendMessage(new TextMessage(node.toString()));
                }
            } catch (Exception e) {
                log.debug("asr send to client failed session={}: {}", id, e.getMessage());
            }
        }

        private ObjectNode base(String type) {
            ObjectNode node = mapper.createObjectNode();
            node.put("type", type);
            return node;
        }

        private final class Listener implements RealtimeAsrGateway.Listener {

            @Override
            public void onStarted() {
                drainPending();
                send(base("ready"));
            }

            @Override
            public void onPartial(int sentenceId, String text) {
                ObjectNode node = base("partial");
                node.put("sentenceId", sentenceId);
                node.put("text", text);
                send(node);
            }

            @Override
            public void onSentence(int sentenceId, String text, int beginMs, int endMs) {
                ObjectNode node = base("sentence");
                node.put("sentenceId", sentenceId);
                node.put("text", text);
                node.put("beginMs", beginMs);
                node.put("endMs", endMs);
                send(node);
            }

            @Override
            public void onFinished(RealtimeAsrGateway.Usage usage) {
                recordUsage(usage);
                ObjectNode node = base("final");
                ObjectNode payload = node.putObject("usage");
                payload.put("durationSeconds", usage.durationSeconds());
                payload.put("inputTokens", usage.inputTokens());
                payload.put("outputTokens", usage.outputTokens());
                payload.put("totalTokens", usage.totalTokens());
                send(node);
                finishAndClose();
            }

            @Override
            public void onFailed(String code, String message) {
                ObjectNode node = base("error");
                node.put("code", code);
                node.put("message", message);
                send(node);
                finishAndClose();
            }
        }
    }

    private static void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (Exception ignored) {
            // 关闭失败无所谓，容器会兜底
        }
    }
}
