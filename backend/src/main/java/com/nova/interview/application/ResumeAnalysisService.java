package com.nova.interview.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nova.ai.infrastructure.bailian.DashScopeProperties;
import com.nova.interview.agent.ModuleSplitAgent;
import com.nova.interview.agent.PointAnalysisAgent;
import com.nova.interview.application.ResumeMarkdownRenderer.Block;
import com.nova.interview.domain.model.ResumeAnalysis;
import com.nova.interview.domain.repository.ResumeAnalysisRepository;
import com.nova.knowledge.infrastructure.parser.DocumentBlock;
import com.nova.knowledge.infrastructure.parser.DocumentBlockType;
import com.nova.knowledge.infrastructure.parser.ParsedDocument;
import com.nova.shared.error.BusinessException;
import com.nova.shared.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 简历深度分析：Markdown 渲染 → 模块切分 → 逐模块小点点评 → 锚点定位 → 落库。
 *
 * <p>锚点定位完全不调模型：模型的职责是"指出哪句话有问题"，定位是确定性的字符串匹配。
 * 这样批注位置可复现，且失败时有明确降级路径（段落级），不会出现批注飘到别人段落上。</p>
 */
@Service
public class ResumeAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(ResumeAnalysisService.class);

    private final ResumeAnalysisRepository analyses;
    private final ModuleSplitAgent splitter;
    private final PointAnalysisAgent pointAgent;
    private final ObjectMapper objectMapper;
    private final String modelName;

    public ResumeAnalysisService(ResumeAnalysisRepository analyses,
                                 ModuleSplitAgent splitter,
                                 PointAnalysisAgent pointAgent,
                                 ObjectMapper objectMapper,
                                 DashScopeProperties dashScope) {
        this.analyses = analyses;
        this.splitter = splitter;
        this.pointAgent = pointAgent;
        this.objectMapper = objectMapper;
        this.modelName = dashScope.getChatModel();
    }

    @Transactional
    public AnalysisView analyze(Long resumeId, ParsedDocument document) {
        ResumeMarkdownRenderer.Rendered rendered = ResumeMarkdownRenderer.render(document);
        List<Block> blocks = rendered.blocks();
        if (blocks.isEmpty()) {
            throw new IllegalStateException("resume produced no blocks");
        }

        List<ModuleSplitAgent.ModuleDraft> drafts = splitter.split(blocks);
        List<ModuleView> modules = new ArrayList<>();
        int anchorMisses = 0;
        int pointSeq = 0;
        for (ModuleSplitAgent.ModuleDraft draft : drafts) {
            String text = joinBlocks(blocks, draft.blockIds());
            if (text.isBlank()) {
                continue;
            }
            List<PointAnalysisAgent.PointDraft> points = pointAgent.analyze(draft.title(), text);
            if (points.isEmpty()) {
                // 整个模块没有可点评的小点（例如只有联系方式），不必在页面上留一个空标题
                continue;
            }
            List<PointView> views = new ArrayList<>();
            for (PointAnalysisAgent.PointDraft point : points) {
                if (isDuplicate(views, point.quote())) {
                    continue;
                }
                Anchor anchor = resolve(blocks, point.quote(), draft.blockIds());
                if (anchor == null) {
                    anchorMisses++;
                }
                views.add(new PointView("p" + (++pointSeq), point.title(), point.quote(), anchor,
                        point.level(), point.comment(), point.improvements(), point.questions()));
            }
            modules.add(new ModuleView(draft.type().name(), draft.title(), summaryOf(text),
                    draft.blockIds(), views));
        }

        if (modules.isEmpty()) {
            throw new IllegalStateException("analysis produced no module with content");
        }
        int points = modules.stream().mapToInt(m -> m.points().size()).sum();
        if (anchorMisses * 100 / Math.max(1, points) > 30) {
            log.warn("Anchor resolution miss rate too high: {}/{}", anchorMisses, points);
        }
        AnalysisView view = new AnalysisView(resumeId, modelName, rendered.markdown(), blocks, modules,
                stats(modules), Instant.now());
        String modulesJson = write(modules);
        String blocksJson = write(blocks);

        ResumeAnalysis entity = analyses.findByResumeId(resumeId).orElse(null);
        if (entity == null) {
            analyses.save(ResumeAnalysis.create(resumeId, rendered.markdown(), blocksJson, modulesJson, modelName));
        } else {
            entity.replace(rendered.markdown(), blocksJson, modulesJson, modelName);
            analyses.save(entity);
        }
        log.info("Resume analyzed: id={}, modules={}, points={}, anchorMisses={}",
                resumeId, modules.size(), points, anchorMisses);
        return view;
    }

    public AnalysisView view(Long resumeId) {
        ResumeAnalysis entity = analyses.findByResumeId(resumeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        List<Block> blocks = read(entity.getBlocksJson(),
                objectMapper.getTypeFactory().constructCollectionType(List.class, Block.class));
        List<ModuleView> modules = read(entity.getModulesJson(),
                objectMapper.getTypeFactory().constructCollectionType(List.class, ModuleView.class));
        return new AnalysisView(resumeId, entity.getModelName(), entity.getMarkdown(), blocks, modules,
                stats(modules), entity.getCreatedAt());
    }

    public String markdown(Long resumeId) {
        return analyses.findByResumeId(resumeId)
                .map(ResumeAnalysis::getMarkdown)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /**
     * 在模块的块里定位引用原文。
     *
     * <p>阶梯式降级：精确匹配 → 归一化匹配 → 段落级兜底。第三步保证批注永远有落点，
     * 只是高亮范围从"一句"退化成"整段"。</p>
     */
    private static Anchor resolve(List<Block> blocks, String quote, List<String> moduleBlockIds) {
        List<Block> scoped = moduleBlockIds.isEmpty()
                ? blocks
                : blocks.stream().filter(b -> moduleBlockIds.contains(b.id())).toList();
        for (Block block : scoped) {
            int index = block.text().indexOf(quote);
            if (index >= 0) {
                return new Anchor(block.id(), index, index + quote.length());
            }
        }
        String normalizedQuote = ResumeMarkdownRenderer.normalize(quote);
        for (Block block : scoped) {
            if (block.text().replaceAll("\\s+", "").contains(normalizedQuote)) {
                return new Anchor(block.id(), 0, Math.min(block.text().length(), quote.length() + 20));
            }
        }
        Block fallback = scoped.isEmpty() ? blocks.get(0) : scoped.get(0);
        return new Anchor(fallback.id(), 0, Math.min(fallback.text().length(), 40));
    }

    /** 同一段文字被拆成两条批注时只保留第一条，避免右侧批注栏出现重复卡片。 */
    private static boolean isDuplicate(List<PointView> views, String quote) {
        String normalized = ResumeMarkdownRenderer.normalize(quote);
        if (normalized.isBlank()) {
            return true;
        }
        for (PointView view : views) {
            String other = ResumeMarkdownRenderer.normalize(view.quote());
            if (other.contains(normalized) || normalized.contains(other)) {
                return true;
            }
        }
        return false;
    }

    private static String joinBlocks(List<Block> blocks, List<String> ids) {
        StringBuilder builder = new StringBuilder();
        for (String id : ids) {
            blocks.stream().filter(b -> b.id().equals(id)).findFirst()
                    .ifPresent(block -> builder.append(block.text()).append('\n'));
        }
        return builder.toString().trim();
    }

    private static String summaryOf(String text) {
        String firstLine = text.lines().findFirst().orElse("").trim();
        return firstLine.length() <= 40 ? firstLine : firstLine.substring(0, 40) + "...";
    }

    private static Stats stats(List<ModuleView> modules) {
        int points = 0;
        int strong = 0;
        int medium = 0;
        int weak = 0;
        for (ModuleView module : modules) {
            for (PointView point : module.points()) {
                points++;
                switch (point.level()) {
                    case "STRONG" -> strong++;
                    case "WEAK" -> weak++;
                    default -> medium++;
                }
            }
        }
        return new Stats(modules.size(), points, strong, medium, weak);
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("cannot serialize analysis", ex);
        }
    }

    private <T> T read(String json, com.fasterxml.jackson.databind.JavaType type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception ex) {
            throw new IllegalStateException("stored analysis is not readable", ex);
        }
    }

    public record Anchor(String blockId, int start, int end) {
    }

    public record PointView(String id, String title, String quote, Anchor anchor, String level,
                            String comment, List<String> improvements, List<String> questions) {
        public PointView {
            level = level == null ? "MEDIUM" : level.toUpperCase(Locale.ROOT);
        }
    }

    public record ModuleView(String type, String title, String summary, List<String> blockIds,
                             List<PointView> points) {
    }

    public record Stats(int modules, int points, int strong, int medium, int weak) {
    }

    public record AnalysisView(Long resumeId, String modelName, String markdown, List<Block> blocks,
                               List<ModuleView> modules, Stats stats, Instant createdAt) {
    }
}
