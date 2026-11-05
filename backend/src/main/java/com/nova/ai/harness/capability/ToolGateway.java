package com.nova.ai.harness.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.nova.ai.harness.AiProgressEvent;
import com.nova.audit.application.AuditRecorder;
import com.nova.audit.domain.AuditEvent;
import com.nova.audit.domain.AuditResult;
import com.nova.shared.security.ActorAccess;
import com.nova.shared.security.CurrentActor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具网关：模型想用某个工具，只有这一个入口。
 *
 * <p>以前"能不能用"要在这里答一遍、那里再答一遍：能力自身的 {@code supports()}、平台的启用清单、
 * 编排器的只读过滤、对话入口的权限校验各管一段，出问题时得把四处串起来看。现在按顺序在这里一次做完：</p>
 *
 * <ol>
 *   <li>工具是否存在；</li>
 *   <li>当前身份是否有权使用（角色 + 权限码 + 是否需要门店）；</li>
 *   <li>模型路径是否只读（写工具默认一律拒绝，只有 {@code nova.ai.tools.allow-model-write=true} 才放行）；</li>
 *   <li>参数是否符合该工具声明的 JSON Schema（含禁止 SQL）；</li>
 *   <li>执行并记录审计日志（谁、哪个工具、参数名、耗时、成败）。</li>
 * </ol>
 *
 * <p>注意：这里只做"能不能调"，不决定"这次对话该带哪些工具"——那是平台配置与角色模板的事。</p>
 */
@Component
public class ToolGateway {

    private static final Logger log = LoggerFactory.getLogger(ToolGateway.class);

    /** 上下文注入类能力，不是工具，永远不进模型的工具候选 */
    public static final Set<String> NON_TOOL_IDS = Set.of("basic_chat", "knowledge_search", "ai_memory");

    /** 一次对话最多执行几个工具 */
    public static final int MAX_TOOL_CALLS = 2;

    private final AiCapabilityRegistry registry;
    private final AuditRecorder auditRecorder;
    /** 是否允许模型路径执行写工具；默认 false，只读是关键不变量 */
    private final boolean allowModelWrite;

    @org.springframework.beans.factory.annotation.Autowired
    public ToolGateway(AiCapabilityRegistry registry, AuditRecorder auditRecorder,
                       @org.springframework.beans.factory.annotation.Value(
                               "${nova.ai.tools.allow-model-write:false}") boolean allowModelWrite) {
        this.registry = registry;
        this.auditRecorder = auditRecorder;
        this.allowModelWrite = allowModelWrite;
    }

    /** 兼容旧构造：手工装配或测试时不落审计表 */
    public ToolGateway(AiCapabilityRegistry registry) {
        this(registry, null, false);
    }

    public ToolGateway(AiCapabilityRegistry registry, AuditRecorder auditRecorder) {
        this(registry, auditRecorder, false);
    }

    /** 模型可见的工具候选：去掉上下文注入能力与非只读工具 */
    public List<AiCapability> listForModel(CurrentActor actor, Collection<String> enabledIds) {
        return registry.listFor(actor, enabledIds).stream()
                .filter(capability -> !NON_TOOL_IDS.contains(capability.descriptor().id()))
                .filter(this::modelCallable)
                .toList();
    }

    /** 模型路径是否可调：只读工具永远可以；写工具仅当全局开关打开 */
    private boolean modelCallable(AiCapability capability) {
        return allowModelWrite || "READ".equalsIgnoreCase(capability.descriptor().type());
    }

    /** 模型发起的工具调用：校验 + 执行 + 审计的唯一入口 */
    public ToolResult callModelTool(CurrentActor actor, String toolId, JsonNode arguments,
                                    ToolExecutionContext context) {
        AiCapability capability = requireAllowed(actor, toolId);
        if (!modelCallable(capability)) {
            reject(actor, toolId, "模型路径不允许执行写工具");
            throw new IllegalArgumentException("首期只允许只读工具");
        }
        validateArguments(arguments, capability.descriptor().parameterSchema());
        return execute(actor, capability, arguments, context);
    }

    /** 非模型路径（调试接口等）：跳过只读限制，其余校验一致 */
    public ToolResult callTool(CurrentActor actor, String toolId, JsonNode arguments, ToolExecutionContext context) {
        return execute(actor, requireAllowed(actor, toolId), arguments, context);
    }

    private AiCapability requireAllowed(CurrentActor actor, String toolId) {
        AiCapability capability = registry.find(toolId).orElse(null);
        if (capability == null) {
            reject(actor, toolId, "工具不支持：" + toolId);
            throw new IllegalArgumentException("工具不支持：" + toolId);
        }
        if (!capability.supports(actor)) {
            reject(actor, toolId, "当前身份无权使用该工具");
            throw new IllegalArgumentException("当前身份无权使用该工具");
        }
        return capability;
    }

    private ToolResult execute(CurrentActor actor, AiCapability capability, JsonNode arguments,
                               ToolExecutionContext context) {
        long started = System.currentTimeMillis();
        ToolResult result = capability.execute(actor, arguments, context);
        long costMs = System.currentTimeMillis() - started;
        // 审计：一次工具调用一条，含耗时，便于排查"为什么这次没查到"
        log.info("AI tool traceId={} actor={} tenant={} tool={} params={} status={} costMs={}",
                context == null ? null : context.traceId(), actor.userId(), ActorAccess.tenantId(actor),
                capability.descriptor().id(), summarizeArguments(arguments),
                result.success() ? "SUCCESS" : "FAILED", costMs);
        audit(actor, capability.descriptor().id(),
                context == null ? null : context.traceId(),
                result.success() ? AuditResult.SUCCESS : AuditResult.FAILURE, null);
        return result;
    }

    /** 拒绝也要留痕：谁在什么时候碰了哪个不该用的工具 */
    private void reject(CurrentActor actor, String toolId, String reason) {
        audit(actor, toolId, null, AuditResult.REJECTED, reason);
    }

    /**
     * 落审计表；任何异常都被吞掉——审计是旁路，不能把工具结果变成 500。
     */
    private void audit(CurrentActor actor, String toolId, String traceId, AuditResult result, String reason) {
        if (auditRecorder == null || actor == null) {
            return;
        }
        try {
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("source", "tool_gateway");
            metadata.put("resourceName", toolId);
            if (reason != null && !reason.isBlank()) {
                metadata.put("reason", reason);
            }
            auditRecorder.record(new AuditEvent(
                    "ai.tool.call",
                    "ai_tool",
                    null,
                    result,
                    traceId == null || traceId.isBlank() ? java.util.UUID.randomUUID().toString() : traceId,
                    metadata));
        } catch (RuntimeException ignored) {
            // 审计失败不影响业务，错误已由上层日志覆盖
        }
    }

    /** 参数校验：必须是对象、不接受 SQL、必填项齐全、基础类型正确 */
    private static void validateArguments(JsonNode arguments, JsonNode schema) {
        if (arguments == null || !arguments.isObject()) {
            throw new IllegalArgumentException("工具参数必须是 JSON 对象");
        }
        if (arguments.has("sql") || arguments.has("querySql") || arguments.has("rawQuery")) {
            throw new IllegalArgumentException("工具不接受 SQL 或任意查询语句");
        }
        JsonNode required = schema == null ? null : schema.path("required");
        if (required != null && required.isArray()) {
            for (JsonNode field : required) {
                if (field.isTextual() && !arguments.has(field.asText())) {
                    throw new IllegalArgumentException("缺少工具参数：" + field.asText());
                }
            }
        }
        JsonNode properties = schema == null ? null : schema.path("properties");
        if (properties != null && properties.isObject()) {
            properties.fields().forEachRemaining(entry -> {
                JsonNode value = arguments.get(entry.getKey());
                if (value == null) {
                    return;
                }
                String type = entry.getValue().path("type").asText("");
                if ("integer".equals(type) && !value.isIntegralNumber()) {
                    throw new IllegalArgumentException("工具参数类型错误：" + entry.getKey());
                }
                if ("string".equals(type) && !value.isTextual()) {
                    throw new IllegalArgumentException("工具参数类型错误：" + entry.getKey());
                }
            });
        }
    }

    /** 参数只记字段名，不落具体值，避免审计表里出现用户隐私 */
    private static String summarizeArguments(JsonNode arguments) {
        if (arguments == null || !arguments.isObject()) {
            return "{}";
        }
        List<String> names = new ArrayList<>();
        arguments.fieldNames().forEachRemaining(names::add);
        return names.toString();
    }

    /** 供编排器发进度事件：工具开始 / 结束 */
    public static AiProgressEvent started(AiCapability capability) {
        return new AiProgressEvent(capability.descriptor().id(), "tool_start", AiProgressEvent.Status.STARTED,
                "正在查询 " + capability.descriptor().name(), null);
    }
}
