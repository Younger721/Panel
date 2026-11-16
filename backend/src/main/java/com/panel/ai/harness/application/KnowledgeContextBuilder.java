package com.panel.ai.harness.application;

import com.panel.ai.domain.ContextSegment;
import com.panel.ai.harness.AiHarnessPlugin;
import com.panel.ai.harness.AiHarnessPluginDescriptor;
import com.panel.ai.harness.AiHarnessPluginRegistry;
import com.panel.ai.harness.AiProgressDetail;
import com.panel.ai.harness.AiProgressEvent;
import com.panel.shared.security.CurrentActor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 上下文注入：把知识库检索结果与旧插件贡献的片段填进 {@code context}。
 *
 * <p>只管"往上下文里放什么"，不做提示词拼接（那是 {@link PromptComposer}），
 * 也不做工具调用（那是网关与编排器）。</p>
 */
public class KnowledgeContextBuilder {

    private final AiHarnessPluginRegistry registry;

    public KnowledgeContextBuilder(AiHarnessPluginRegistry registry) {
        this.registry = registry;
    }

    /**
     * 知识库检索：直接调用 knowledge_search 插件，返回本次新增的片段。
     */
    public List<ContextSegment> retrieve(CurrentActor actor, String question, List<ContextSegment> context,
                                         Consumer<AiProgressEvent> progress) {
        List<ContextSegment> retrieved = new ArrayList<>();
        registry.find("knowledge_search").ifPresent(plugin -> {
            int contextBefore = context.size();
            progress.accept(new AiProgressEvent("knowledge_search", "plugin_start", AiProgressEvent.Status.STARTED,
                    "自动检索知识库", null));
            try {
                plugin.contribute(actor, question, context, progress);
                retrieved.addAll(context.subList(contextBefore, context.size()));
                progress.accept(new AiProgressEvent("knowledge_search", "plugin_complete", AiProgressEvent.Status.COMPLETED,
                        "知识库检索完成", retrieved.size(), documentDetails(retrieved)));
            } catch (RuntimeException exception) {
                progress.accept(new AiProgressEvent("knowledge_search", "plugin_error", AiProgressEvent.Status.FAILED,
                        "知识库检索失败", null));
                throw exception;
            }
        });
        return List.copyOf(retrieved);
    }

    /**
     * 非原生工具流时，用旧插件把业务数据写进上下文（与知识库检索互不重复）。
     */
    public void contributePlugins(CurrentActor actor, String question, List<String> enabled,
                                  List<ContextSegment> context, boolean knowledgeRetrieved,
                                  Consumer<AiProgressEvent> progress) {
        for (String pluginId : enabled) {
            if ("basic_chat".equals(pluginId) || (knowledgeRetrieved && "knowledge_search".equals(pluginId))) {
                continue;
            }
            registry.find(pluginId).ifPresent(plugin -> {
                AiHarnessPluginDescriptor descriptor = plugin.descriptor();
                int contextBefore = context.size();
                String startMessage = "knowledge_search".equals(pluginId)
                        ? "启动 RAG 插件"
                        : "gym_operations_metrics".equals(pluginId) ? "正在查询门店运营数据" : "启动" + descriptor.name();
                String completeMessage = "knowledge_search".equals(pluginId)
                        ? "RAG 检索完成"
                        : "gym_operations_metrics".equals(pluginId) ? "门店运营数据查询完成" : descriptor.name() + "完成";
                progress.accept(new AiProgressEvent(pluginId, "plugin_start", AiProgressEvent.Status.STARTED,
                        startMessage, null));
                try {
                    plugin.contribute(actor, question, context, progress);
                    progress.accept(new AiProgressEvent(pluginId, "plugin_complete", AiProgressEvent.Status.COMPLETED,
                            completeMessage, context.size() - contextBefore,
                            "knowledge_search".equals(pluginId)
                                    ? documentDetails(context.subList(contextBefore, context.size()))
                                    : List.of()));
                } catch (RuntimeException exception) {
                    String errorMessage = "knowledge_search".equals(pluginId)
                            ? "RAG 插件执行失败"
                            : descriptor.name() + "执行失败";
                    progress.accept(new AiProgressEvent(pluginId, "plugin_error", AiProgressEvent.Status.FAILED,
                            errorMessage, null));
                    throw exception;
                }
            });
        }
    }

    /** 把检索片段转成前端可展示的引用明细，最多 20 条、每条截断 300 字 */
    public static List<AiProgressDetail> documentDetails(List<ContextSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return List.of();
        }
        List<AiProgressDetail> details = new ArrayList<>();
        for (int i = 0; i < segments.size() && details.size() < 20; i++) {
            ContextSegment segment = segments.get(i);
            String text = segment.text() == null ? "" : segment.text();
            details.add(new AiProgressDetail("DOCUMENT", "最终引用",
                    segment.documentId(), segment.id(), null,
                    text.length() <= 300 ? text : text.substring(0, 300), i + 1));
        }
        return details;
    }
}
