package com.panel.interview.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.panel.ai.application.ChatModelGateway;
import com.panel.ai.domain.ContextSegment;
import com.panel.interview.application.ResumeMarkdownRenderer.Block;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 把简历的块序列归入固定模块类型。
 *
 * <p>模块类型是固定枚举而不是让模型自由命名：两次分析同一份简历必须得到同样的模块名，
 * 否则历史对比、批注筛选都没法做。</p>
 */
@Component
public class ModuleSplitAgent {

    private static final Logger log = LoggerFactory.getLogger(ModuleSplitAgent.class);

    public enum ModuleType {
        INTRO, EDUCATION, INTERNSHIP, PROJECT, TECH_STACK, OTHER
    }

    public record ModuleDraft(ModuleType type, String title, List<String> blockIds) {
    }

    private static final String INSTRUCTION = """
            你是简历结构分析器。下面是一份简历按原文顺序切分出的文本块，每块有一个 id。
            请把这些块归入以下模块类型：
            INTRO 个人介绍/求职意向、EDUCATION 教育背景、INTERNSHIP 实习或工作经历、
            PROJECT 项目经历、TECH_STACK 专业技能、OTHER 其他。

            只输出 JSON，不要 Markdown 代码块，不要解释：
            {"modules":[{"type":"PROJECT","title":"项目经历","blockIds":["b5","b6"]}]}

            约束：
            1. 只能使用给定的块 id，每个块最多属于一个模块；
            2. 按原文顺序输出，没有内容的模块不要输出；
            3. title 用中文，例如「项目经历」。
            """;

    private final ChatModelGateway gateway;
    private final ObjectMapper objectMapper;

    public ModuleSplitAgent(ChatModelGateway gateway, ObjectMapper objectMapper) {
        this.gateway = gateway;
        this.objectMapper = objectMapper;
    }

    public List<ModuleDraft> split(List<Block> blocks) {
        if (blocks.isEmpty()) {
            throw new IllegalStateException("resume has no blocks to split");
        }
        String prompt = INSTRUCTION + "\n简历块：\n" + renderBlocks(blocks);
        var result = gateway.completeStructuredJson(prompt,
                List.of(new ContextSegment("resume-blocks", "resume", renderBlocks(blocks), "blocks", 1, "RESUME")));
        JsonNode root = AgentJson.parse(objectMapper, result.content(), JsonNode.class, "ModuleSplit");
        Set<String> known = new LinkedHashSet<>(blocks.stream().map(Block::id).toList());
        List<ModuleDraft> modules = new ArrayList<>();
        for (JsonNode node : root.path("modules")) {
            ModuleType type = parseType(node.path("type").asText(""));
            List<String> ids = new ArrayList<>();
            for (JsonNode id : node.path("blockIds")) {
                String value = id.asText("");
                if (known.contains(value)) {
                    ids.add(value);
                }
            }
            if (ids.isEmpty()) {
                continue;
            }
            String title = node.path("title").asText("").trim();
            modules.add(new ModuleDraft(type, title.isBlank() ? type.name() : title, ids));
        }
        if (modules.isEmpty()) {
            throw new IllegalStateException("module splitter returned no usable module");
        }
        log.info("Resume split into {} modules: {}", modules.size(),
                modules.stream().map(m -> m.type().name()).toList());
        return modules;
    }

    private static ModuleType parseType(String raw) {
        try {
            return ModuleType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return ModuleType.OTHER;
        }
    }

    private static String renderBlocks(List<Block> blocks) {
        StringBuilder builder = new StringBuilder();
        for (Block block : blocks) {
            String text = block.text().length() > 300 ? block.text().substring(0, 300) : block.text();
            builder.append(block.id()).append(" [").append(block.type()).append("] ").append(text).append('\n');
        }
        return builder.toString();
    }
}
