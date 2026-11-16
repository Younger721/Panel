package com.panel.interview.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.panel.ai.application.AiUsageCommand;
import com.panel.ai.application.DefaultAiUsageService;
import com.panel.ai.application.RealtimeAsrGateway;
import com.panel.ai.infrastructure.bailian.AsrProperties;
import com.panel.iam.domain.model.RoleCode;
import com.panel.interview.application.AsrQuotaService;
import com.panel.interview.application.AsrTicketService;
import com.panel.shared.security.CurrentActor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖「百炼事件 → 浏览器帧」的映射，这是前后端之间唯一的契约。
 */
class AsrWebSocketHandlerTest {

    private FakeGateway gateway;
    private DefaultAiUsageService usage;
    private AsrQuotaService quota;
    private AsrWebSocketHandler handler;
    private ObjectMapper mapper;
    private WebSocketSession session;
    private AsrTicketService.Ticket ticket;

    @BeforeEach
    void setUp() {
        gateway = new FakeGateway();
        usage = mock(DefaultAiUsageService.class);
        quota = new AsrQuotaService(new AsrProperties());
        mapper = new ObjectMapper();
        handler = new AsrWebSocketHandler(gateway, usage, quota, mapper, 90);

        CurrentActor actor = new CurrentActor(7L, 1L, Set.of(RoleCode.CANDIDATE), Set.of("interview:answer"), 0, "jti");
        ticket = new AsrTicketService.Ticket("tk", 42L, 7L,
                "qwen-audio-3.1-asr-flash-message", Instant.now().plusSeconds(30), actor);

        Map<String, Object> attributes = new HashMap<>();
        attributes.put(AsrWebSocketHandler.ATTR_TICKET, ticket);
        session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s-1");
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenReturn(true);
    }

    private List<JsonNode> frames() throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeastOnce()).sendMessage(captor.capture());
        List<JsonNode> result = new ArrayList<>();
        for (TextMessage message : captor.getAllValues()) {
            JsonNode node = mapper.readTree(message.getPayload());
            if (node.has("type")) {
                result.add(node);
            }
        }
        return result;
    }

    @Test
    void rejectsConnectionWithoutTicket() throws Exception {
        WebSocketSession anonymous = mock(WebSocketSession.class);
        when(anonymous.getId()).thenReturn("s-x");
        when(anonymous.getAttributes()).thenReturn(new HashMap<>());

        handler.afterConnectionEstablished(anonymous);

        verify(anonymous).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void mapsUpstreamEventsToClientFrames() throws Exception {
        handler.afterConnectionEstablished(session);

        gateway.listener.onStarted();
        gateway.listener.onPartial(1, "我之前在上");
        gateway.listener.onSentence(1, "我之前在上一家公司。", 100, 2200);
        gateway.listener.onFinished(new RealtimeAsrGateway.Usage(3, 40, 12, 52));

        List<JsonNode> frames = frames();
        assertThat(frames.get(0).path("type").asText()).isEqualTo("ready");
        assertThat(frames.get(1).path("type").asText()).isEqualTo("partial");
        assertThat(frames.get(1).path("text").asText()).isEqualTo("我之前在上");
        assertThat(frames.get(2).path("type").asText()).isEqualTo("sentence");
        assertThat(frames.get(2).path("sentenceId").asInt()).isEqualTo(1);
        assertThat(frames.get(3).path("type").asText()).isEqualTo("final");
        assertThat(frames.get(3).path("usage").path("totalTokens").asInt()).isEqualTo(52);

        ArgumentCaptor<AiUsageCommand> command = ArgumentCaptor.forClass(AiUsageCommand.class);
        verify(usage).record(any(), command.capture());
        assertThat(command.getValue().feature()).isEqualTo("INTERVIEW_ASR");
        assertThat(command.getValue().totalTokens()).isEqualTo(52);
        assertThat(command.getValue().status()).isEqualTo("SUCCESS");
    }

    @Test
    void forwardsPcmFramesToUpstream() throws Exception {
        handler.afterConnectionEstablished(session);
        gateway.listener.onStarted();

        byte[] pcm = new byte[3200];
        pcm[0] = 1;
        handler.handleBinaryMessage(session, new BinaryMessage(pcm));

        assertThat(gateway.session.frames).hasSize(1);
        assertThat(gateway.session.frames.get(0)).hasSize(3200);
    }

    @Test
    void buffersAudioUntilUpstreamIsReady() throws Exception {
        handler.afterConnectionEstablished(session);

        // 前端 WS 一打开就推帧，此时百炼还没 task-started
        handler.handleBinaryMessage(session, new BinaryMessage(new byte[3200]));
        handler.handleBinaryMessage(session, new BinaryMessage(new byte[3200]));
        assertThat(gateway.session.frames).isEmpty();

        gateway.listener.onStarted();

        // 攒下的两帧要补发，否则开头一两个字会丢
        assertThat(gateway.session.frames).hasSize(2);
    }

    @Test
    void finishAndCancelDriveUpstream() throws Exception {
        handler.afterConnectionEstablished(session);
        gateway.listener.onStarted();

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"finish\"}"));
        assertThat(gateway.session.finished).isTrue();

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"cancel\"}"));
        assertThat(gateway.session.closed).isTrue();
    }

    /**
     * 上游还在握手时用户就说完了。
     *
     * <p>finish 直接发给上游会被它丢掉（它还在 run-task），用户永远等不到 final，
     * 前端几秒后报「识别超时」——话白说了，配额也扣了。必须等 ready 再补发，
     * 而且顺序是「先冲缓冲的音频，再说我说完了」。</p>
     */
    @Test
    void finishBeforeUpstreamReadyIsDeferredUntilStarted() throws Exception {
        handler.afterConnectionEstablished(session);

        handler.handleBinaryMessage(session, new BinaryMessage(new byte[3200]));
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"finish\"}"));
        assertThat(gateway.session.finished).isFalse();

        gateway.listener.onStarted();

        assertThat(gateway.session.frames).hasSize(1);
        assertThat(gateway.session.finished).isTrue();
    }

    @Test
    void upstreamFailureIsReportedAndDoesNotLeakException() throws Exception {
        handler.afterConnectionEstablished(session);

        gateway.listener.onFailed("UPSTREAM_AUTH", "unauthorized");

        List<JsonNode> frames = frames();
        assertThat(frames).anyMatch(node ->
                "error".equals(node.path("type").asText())
                        && "UPSTREAM_AUTH".equals(node.path("code").asText()));
        verify(session).close(any(CloseStatus.class));
    }

    @Test
    void releasesConcurrencySlotWhenUpstreamFails() throws Exception {
        // 并发位由握手拦截器占用，这里手动模拟
        assertThat(quota.acquireConnection(7L)).isTrue();
        handler.afterConnectionEstablished(session);
        assertThat(quota.acquireConnection(7L)).isFalse();

        gateway.listener.onFailed("UPSTREAM_ERROR", "boom");

        assertThat(quota.acquireConnection(7L)).isTrue();
    }

    @Test
    void ignoresAudioAfterClose() throws Exception {
        handler.afterConnectionEstablished(session);
        gateway.listener.onFinished(RealtimeAsrGateway.Usage.empty());

        handler.handleBinaryMessage(session, new BinaryMessage(new byte[16]));

        assertThat(gateway.session.frames).isEmpty();
        verify(session, never()).sendMessage(any(BinaryMessage.class));
    }

    /** 记录收到的音频与控制指令，替代真实 WebSocket。 */
    private static final class FakeGateway implements RealtimeAsrGateway {

        final FakeSession session = new FakeSession();
        Listener listener;

        @Override
        public Session open(OpenOptions options) {
            this.listener = options.listener();
            return session;
        }
    }

    private static final class FakeSession implements RealtimeAsrGateway.Session {

        final List<byte[]> frames = new ArrayList<>();
        boolean finished;
        boolean closed;

        @Override
        public void send(byte[] pcm) {
            if (closed) {
                return;
            }
            frames.add(pcm);
        }

        @Override
        public void finish() {
            finished = true;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
