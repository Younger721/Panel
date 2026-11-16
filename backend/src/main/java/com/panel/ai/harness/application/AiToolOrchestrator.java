package com.panel.ai.harness.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.panel.ai.application.ChatModelGateway;
import com.panel.ai.application.ChatModelResponse;
import com.panel.ai.application.ChatToolCall;
import com.panel.ai.application.ChatToolDefinition;
import com.panel.ai.domain.ContextSegment;
import com.panel.ai.harness.AiProgressEvent;
import com.panel.ai.harness.capability.AiCapability;
import com.panel.ai.harness.capability.AiCapabilityRegistry;
import com.panel.ai.harness.capability.ToolExecutionContext;
import com.panel.ai.harness.capability.ToolGateway;
import com.panel.ai.harness.capability.ToolResult;
import com.panel.shared.security.CurrentActor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 工具编排：让模型自己决定调哪个工具，然后把调用交给 {@link ToolGateway}。
 *
 * <p>本类只负责"决策与循环"，不再重复做身份、权限、只读、参数校验与审计——
 * 那些统一在网关里，避免同一条规则散落多处、改一处漏一处。</p>
 */
@org.springframework.stereotype.Component
public class AiToolOrchestrator {

    public static final int MAX_TOOL_CALLS = ToolGateway.MAX_TOOL_CALLS;

    private static final Logger log = LoggerFactory.getLogger(AiToolOrchestrator.class);

    private final ChatModelGateway model;
    private final ObjectMapper mapper;
    private final ToolGateway gateway;

    @org.springframework.beans.factory.annotation.Autowired
    public AiToolOrchestrator(ChatModelGateway model, ObjectMapper mapper, ToolGateway gateway) {
        this.model = model;
        this.mapper = mapper;
        this.gateway = gateway;
    }

    /** 兼容旧构造：手工装配时用能力表自建一个网关 */
    public AiToolOrchestrator(AiCapabilityRegistry registry, ChatModelGateway model, ObjectMapper mapper) {
        this(model, mapper, new ToolGateway(registry));
    }

    public AiToolOrchestrationResult decide(CurrentActor actor, String question,
                                             List<ContextSegment> context,
                                             Consumer<AiProgressEvent> progress) {
        return decide(actor, question, context, List.of(), progress);
    }

    public AiToolOrchestrationResult decide(CurrentActor actor, String question,
                                             List<ContextSegment> context,
                                             Collection<String> enabledIds,
                                             Consumer<AiProgressEvent> progress) {
        return decide(actor, question, context, enabledIds, List.of(), progress);
    }

    public AiToolOrchestrationResult decide(CurrentActor actor, String question,
                                             List<ContextSegment> context,
                                             Collection<String> enabledIds,
                                             Collection<String> preferredToolIds,
                                             Consumer<AiProgressEvent> progress) {
        List<AiCapability> capabilities = gateway.listForModel(actor, enabledIds);
        if (preferredToolIds != null && !preferredToolIds.isEmpty()) {
            List<AiCapability> narrowed = capabilities.stream()
                    .filter(capability -> preferredToolIds.contains(capability.descriptor().id()))
                    .toList();
            if (!narrowed.isEmpty()) {
                capabilities = narrowed;
            } else {
                log.warn("preferredToolIds {} 与可用工具无交集，回退全量只读工具", preferredToolIds);
            }
        }
        Map<String, AiCapability> byId = new LinkedHashMap<>();
        capabilities.forEach(capability -> byId.put(capability.descriptor().id(), capability));
        List<ChatToolDefinition> definitions = capabilities.stream()
                .map(c -> new ChatToolDefinition(c.descriptor().id(), c.descriptor().description(), c.descriptor().parameterSchema()))
                .toList();
        long started = System.currentTimeMillis();
        // 仅知识库问答时没有可注册只读工具，跳过带 tool_choice 的决策请求，避免百炼空 tools 报错
        if (definitions.isEmpty()) {
            progress.accept(new AiProgressEvent("orchestrator", "tool_decision", AiProgressEvent.Status.COMPLETED,
                    "无业务只读工具，直接生成回答", 0));
            return new AiToolOrchestrationResult(null, List.of(), List.of());
        }
        ChatModelResponse response = model.completeWithTools(question, context, definitions);
        if (response == null) {
            return new AiToolOrchestrationResult(null, List.of(), List.of());
        }
        if (!response.hasToolCalls()) {
            progress.accept(new AiProgressEvent("orchestrator", "tool_decision", AiProgressEvent.Status.COMPLETED,
                    "直接回答", 0));
            return new AiToolOrchestrationResult(response.content(), List.of(), List.of(), response.usage(),
                    Math.max(0, System.currentTimeMillis() - started));
        }
        if (response.toolCalls().size() > MAX_TOOL_CALLS) {
            throw new IllegalArgumentException("一次最多执行 2 个只读工具");
        }
        String traceId = UUID.randomUUID().toString();
        progress.accept(new AiProgressEvent("orchestrator", "tool_decision", AiProgressEvent.Status.COMPLETED,
                "已识别需要查询业务数据", response.toolCalls().size()));
        List<ToolResult> results = new ArrayList<>();
        List<ContextSegment> toolContext = new ArrayList<>();
        for (ChatToolCall call : response.toolCalls()) {
            AiCapability capability = byId.get(call.name());
            if (capability == null) {
                throw new IllegalArgumentException("工具不支持：" + call.name());
            }
            progress.accept(ToolGateway.started(capability));
            ToolResult result = gateway.callModelTool(actor, call.name(), call.arguments(),
                    new ToolExecutionContext(traceId, question, progress));
            results.add(result);
            toolContext.addAll(result.context());
            progress.accept(new AiProgressEvent(call.name(), "tool_result",
                    result.success() ? AiProgressEvent.Status.COMPLETED : AiProgressEvent.Status.FAILED,
                    result.success() ? "查询完成" : "数据暂时不可用", result.context().size()));
        }
        return new AiToolOrchestrationResult(null, results, toolContext, response.usage(),
                Math.max(0, System.currentTimeMillis() - started));
    }

    /**
     * 预取工具结果（TOOLS_ONLY 路径专用）：同样经过网关校验与审计，不绕过任何限制。
     */
    public void executePreferredTool(CurrentActor actor, String question, AiCapability capability,
                                     List<ContextSegment> context, Consumer<AiProgressEvent> progress) {
        progress.accept(ToolGateway.started(capability));
        ToolResult result = gateway.callModelTool(actor, capability.descriptor().id(),
                mapper.createObjectNode(), new ToolExecutionContext(UUID.randomUUID().toString(), question, progress));
        context.addAll(result.context());
        progress.accept(new AiProgressEvent(capability.descriptor().id(), "tool_result",
                result.success() ? AiProgressEvent.Status.COMPLETED : AiProgressEvent.Status.FAILED,
                result.success() ? "查询完成" : "数据暂时不可用", result.context().size()));
    }

    /** 供测试与诊断：当前身份可见的模型工具 id */
    public List<String> modelToolIds(CurrentActor actor, Collection<String> enabledIds) {
        return gateway.listForModel(actor, enabledIds).stream()
                .map(capability -> capability.descriptor().id())
                .toList();
    }

    /** 保留旧行为：参数摘要只记字段名 */
    static String summarizeArguments(JsonNode arguments) {
        if (arguments == null || !arguments.isObject()) {
            return "{}";
        }
        List<String> names = new ArrayList<>();
        arguments.fieldNames().forEachRemaining(names::add);
        return names.toString();
    }
}
