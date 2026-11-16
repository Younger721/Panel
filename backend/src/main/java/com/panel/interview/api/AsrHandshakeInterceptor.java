package com.panel.interview.api;

import com.panel.interview.application.AsrQuotaService;
import com.panel.interview.application.AsrTicketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * 握手鉴权：验券 + 占并发位。
 *
 * <p>浏览器的 WebSocket 握发送不了 Authorization 头，所以身份靠一次性 ticket，
 * 这里取走即作废。失败直接回 403/429，压根不会去连百炼。</p>
 *
 * <p><b>失败路径必须归位</b>：券在 {@code beforeHandshake} 里就被 consume 掉了，
 * 配额也已经预扣。一旦随后握手本身失败（{@code afterHandshake} 带异常），
 * 连接成不了，也就永远不会走 {@code afterConnectionClosed}——不在这里释放的话，
 * 这个用户的并发位就永久 -1（重启才能恢复），配额也白扣一次。</p>
 */
@Component
public class AsrHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AsrHandshakeInterceptor.class);

    /**
     * 握手阶段的暂存：{@code afterHandshake} 拿不到 attributes，
     * 只能用请求对象把 userId 带到失败回调里。WeakHashMap 不会留垃圾。
     */
    private static final Map<ServerHttpRequest, Long> IN_FLIGHT =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final AsrTicketService tickets;
    private final AsrQuotaService quota;

    public AsrHandshakeInterceptor(AsrTicketService tickets, AsrQuotaService quota) {
        this.tickets = tickets;
        this.quota = quota;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        String token = queryParam(request.getURI().getQuery(), "ticket");
        Optional<AsrTicketService.Ticket> candidate = tickets.consume(token);
        if (candidate.isEmpty()) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            log.debug("asr handshake rejected: invalid ticket");
            return false;
        }
        AsrTicketService.Ticket ticket = candidate.get();
        if (!quota.acquireConnection(ticket.userId())) {
            // 券已作废、配额已扣，但这次连接根本没建立：把配额还回去
            quota.refundDaily(ticket.userId());
            response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            log.debug("asr handshake rejected: too many connections user={}", ticket.userId());
            return false;
        }
        attributes.put(AsrWebSocketHandler.ATTR_TICKET, ticket);
        IN_FLIGHT.put(request, ticket.userId());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
        Long userId = IN_FLIGHT.remove(request);
        if (exception != null && userId != null) {
            // 握手失败：连接不会建立，afterConnectionClosed 也不会触发
            quota.releaseConnection(userId);
            quota.refundDaily(userId);
            log.debug("asr handshake failed, slot and quota released: user={}", userId);
        }
    }

    private static String queryParam(String query, String name) {
        if (query == null || query.isBlank()) {
            return null;
        }
        for (String part : query.split("&")) {
            int separator = part.indexOf('=');
            if (separator > 0 && name.equals(part.substring(0, separator))) {
                return URLDecoder.decode(part.substring(separator + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
