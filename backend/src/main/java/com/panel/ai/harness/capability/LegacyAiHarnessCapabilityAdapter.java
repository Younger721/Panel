package com.panel.ai.harness.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.panel.ai.domain.ContextSegment;
import com.panel.ai.harness.AiHarnessPlugin;
import com.panel.ai.harness.AiHarnessPluginDescriptor;
import com.panel.shared.security.CurrentActor;

import java.util.ArrayList;
import java.util.List;

/**
 * 把旧插件适配成能力协议，让两套体系共用同一个工具目录。
 *
 * <p>插件的入参 schema 由 {@link AiHarnessPlugin#inputSchema()} 提供；没有 schema 的插件
 * （例如纯上下文注入）退化成空对象，仍可出现在目录里但不需要参数。</p>
 */
public final class LegacyAiHarnessCapabilityAdapter implements AiCapability {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AiHarnessPlugin plugin;
    private final CapabilityDescriptor descriptor;

    public LegacyAiHarnessCapabilityAdapter(AiHarnessPlugin plugin) {
        this.plugin = plugin;
        AiHarnessPluginDescriptor d = plugin.descriptor();
        this.descriptor = new CapabilityDescriptor(d.id(), d.name(), d.description(),
                "import_article".equals(d.id()) ? "WRITE" : "READ",
                d.permission(), d.requiresTenant(), java.util.Set.of(),
                schemaOf(plugin),
                CapabilityOrigin.LEGACY, CapabilityDescriptor.DEFAULT_TIMEOUT_MS);
    }

    @Override public CapabilityDescriptor descriptor() { return descriptor; }
    @Override public boolean supports(CurrentActor actor) { return plugin.supports(actor); }

    @Override
    public ToolResult execute(CurrentActor actor, JsonNode arguments, ToolExecutionContext context) {
        List<ContextSegment> segments = new ArrayList<>();
        // 模型带了 query 就用模型的参数；没带（旧路径 / 上下文注入）才退回整句问题
        String input = queryOf(arguments, context);
        if (context == null || context.progress() == null) {
            plugin.contribute(actor, input, segments);
        } else {
            plugin.contribute(actor, input, segments, context.progress());
        }
        String text = segments.stream().map(ContextSegment::text).reduce("", (a, b) -> a + "\n" + b).trim();
        return new ToolResult(true, text, segments);
    }

    /** 取模型下发的 query；缺省回落到当前问题，保持旧插件的行为 */
    private static String queryOf(JsonNode arguments, ToolExecutionContext context) {
        JsonNode query = arguments == null ? null : arguments.get("query");
        if (query != null && query.isTextual() && !query.asText().isBlank()) {
            return query.asText();
        }
        return context == null || context.question() == null ? "" : context.question();
    }

    private static JsonNode schemaOf(AiHarnessPlugin plugin) {
        String raw = plugin.inputSchema();
        if (raw == null || raw.isBlank()) {
            return JsonNodeFactory.instance.objectNode();
        }
        try {
            return MAPPER.readTree(raw);
        } catch (Exception ignored) {
            return JsonNodeFactory.instance.objectNode();
        }
    }
}
