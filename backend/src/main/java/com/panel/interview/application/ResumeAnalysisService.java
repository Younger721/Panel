package com.panel.interview.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.panel.ai.infrastructure.bailian.DashScopeProperties;
import com.panel.interview.agent.ModuleSplitAgent;
import com.panel.interview.agent.PointAnalysisAgent;
import com.panel.interview.application.ResumeMarkdownRenderer.Block;
import com.panel.interview.domain.model.ResumeAnalysis;
import com.panel.interview.domain.repository.ResumeAnalysisRepository;
import com.panel.knowledge.infrastructure.parser.DocumentBlock;
import com.panel.knowledge.infrastructure.parser.DocumentBlockType;
import com.panel.knowledge.infrastructure.parser.ParsedDocument;
import com.panel.shared.error.BusinessException;
import com.panel.shared.error.ErrorCode;
import com.panel.shared.text.SentenceSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 简历深度分析：Markdown 渲染 → 模块切分 → 逐模块小点点评 → 锚点定位 → 落库。
 *
 * <p>锚点定位完全不调模型：模型的职责是"指出哪句话有问题"，定位是确定性的字符串匹配。
 * 这样批注位置可复现，且失败时有明确降级路径（段落级），不会出现批注飘到别人段落上。</p>
 */
@Service
public class ResumeAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(ResumeAnalysisService.class);

    /**
     * 逐模块点评的并发上限：一份简历 4~6 个模块，压到 4 路以内避免打满模型侧 QPS。
     *
     * <p>曾经以为长模块失败是并发连接数限制，降到 2 试过——没用，真正的瓶颈是
     * {@code BailianChatAdapter} 的 30 秒读超时。超时修好后回到 4：一份 6 模块的
     * 简历从 114 秒回到 40 秒左右。</p>
     *
     * <p>现在改成可配置（{@code panel.interview.analysis.max-parallel-modules}）：
     * 4 这个数字是拿单份简历试出来的，批量入库时模型侧 QPS 是共享的，
     * 得按实际配额调。下限 1、上限 8——再多对单份简历没有意义（模块就那么多），
     * 只会让失败重试把配额吃掉。</p>
     */
    private static final int DEFAULT_MAX_PARALLEL_MODULES = 4;
    private static final int MIN_PARALLEL_MODULES = 1;
    private static final int MAX_PARALLEL_LIMIT = 8;

    /** 手机号 / 邮箱：扩整句时不许跨过去，联系方式不该被高亮。 */
    private static final Pattern CONTACT = Pattern.compile("(1[3-9]\\d{9}|@[\\w.-]+\\.\\w+)");

    /** 点评里被引号点名的说法：「双Topic」、“理解”、'SQL 调优'。 */
    private static final Pattern QUOTED = Pattern.compile("[「“\"'‘]([^」”\"'’\\n]{2,24})[」”\"'’]");

    private final ResumeAnalysisRepository analyses;
    private final ModuleSplitAgent splitter;
    private final PointAnalysisAgent pointAgent;
    private final ObjectMapper objectMapper;
    private final String modelName;
    private final TransactionTemplate tx;
    private final int maxParallelModules;

    public ResumeAnalysisService(ResumeAnalysisRepository analyses,
                                 ModuleSplitAgent splitter,
                                 PointAnalysisAgent pointAgent,
                                 ObjectMapper objectMapper,
                                 DashScopeProperties dashScope,
                                 PlatformTransactionManager transactionManager,
                                 @Value("${panel.interview.analysis.max-parallel-modules:4}")
                                 int maxParallelModules) {
        this.analyses = analyses;
        this.splitter = splitter;
        this.pointAgent = pointAgent;
        this.objectMapper = objectMapper;
        this.modelName = dashScope.getChatModel();
        this.tx = new TransactionTemplate(transactionManager);
        this.maxParallelModules = Math.max(MIN_PARALLEL_MODULES,
                Math.min(MAX_PARALLEL_LIMIT, maxParallelModules <= 0 ? DEFAULT_MAX_PARALLEL_MODULES : maxParallelModules));
    }

    /**
     * 简历深度分析。
     *
     * <p><b>整段没有 {@code @Transactional}</b>：里面是「模块切分 + 逐模块点评」十几次模型调用，
     * 一份 PDF 简历实测能跑 60~120 秒。原来整个方法挂在一个事务里，等于占着 Hikari 连接
     * 两分钟不放（池子只有 10 个），三份简历同时上传就能把连接池抽干，其它接口全部排队超时。
     * 现在只有最后那次写入进事务，毫秒级。</p>
     */
    public AnalysisView analyze(Long resumeId, ParsedDocument document, Long ownerUserId) {
        ResumeMarkdownRenderer.Rendered rendered = ResumeMarkdownRenderer.render(document);
        List<Block> blocks = rendered.blocks();
        if (blocks.isEmpty()) {
            throw new IllegalStateException("resume produced no blocks");
        }

        List<ModuleSplitAgent.ModuleDraft> drafts = splitter.split(blocks);
        List<ModuleWork> works = new ArrayList<>();
        for (ModuleSplitAgent.ModuleDraft draft : drafts) {
            String text = joinBlocks(blocks, draft.blockIds());
            if (text.isBlank()) {
                continue;
            }
            works.add(new ModuleWork(draft, text));
        }
        // 逐模块点评是彼此独立的模型调用：并行跑，等待时间从"模块数 × 单次耗时"
        // 压到接近"一次耗时"。结果按模块原顺序返回，批注编号因此保持稳定。
        List<ModuleAnalysis> analyzed = analyzeInParallel(works, ownerUserId);

        List<ModuleView> modules = new ArrayList<>();
        int anchorMisses = 0;
        int pointSeq = 0;
        for (ModuleAnalysis analysis : analyzed) {
            if (analysis.points().isEmpty()) {
                // 整个模块没有可点评的小点（例如只有联系方式），不必在页面上留一个空标题
                continue;
            }
            ModuleSplitAgent.ModuleDraft draft = analysis.work().draft();
            String text = analysis.work().text();
            List<PointView> views = new ArrayList<>();
            for (PointAnalysisAgent.PointDraft point : analysis.points()) {
                if (isDuplicate(views, point.quote())) {
                    continue;
                }
                Anchor anchor = resolve(blocks, point.quote(), draft.blockIds());
                if (anchor == null) {
                    anchorMisses++;
                }
                views.add(new PointView("p" + (++pointSeq), point.title(), point.quote(), anchor,
                        point.level(), point.comment(), point.improvements(), point.questions(),
                        focusOf(point.comment(), text)));
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

        String markdown = rendered.markdown();
        tx.executeWithoutResult(status -> {
            ResumeAnalysis entity = analyses.findByResumeId(resumeId).orElse(null);
            if (entity == null) {
                analyses.save(ResumeAnalysis.create(resumeId, markdown, blocksJson, modulesJson, modelName));
            } else {
                entity.replace(markdown, blocksJson, modulesJson, modelName);
                analyses.save(entity);
            }
        });
        log.info("Resume analyzed: id={}, modules={}, points={}, anchorMisses={}",
                resumeId, modules.size(), points, anchorMisses);
        return view;
    }

    /** 并行跑各模块的点评，结果按模块原顺序返回，保证批注编号稳定。 */
    private List<ModuleAnalysis> analyzeInParallel(List<ModuleWork> works, Long ownerUserId) {
        if (works.isEmpty()) {
            return List.of();
        }
        if (works.size() == 1) {
            ModuleWork only = works.get(0);
            return List.of(new ModuleAnalysis(only,
                    pointAgent.analyze(only.draft().title(), only.text(), ownerUserId)));
        }
        int workers = Math.min(maxParallelModules, works.size());
        List<Future<ModuleAnalysis>> futures = new ArrayList<>(works.size());
        try (ExecutorService executor = Executors.newFixedThreadPool(workers)) {
            for (ModuleWork work : works) {
                futures.add(executor.submit(() -> new ModuleAnalysis(work,
                        pointAgent.analyze(work.draft().title(), work.text(), ownerUserId))));
            }
            List<ModuleAnalysis> results = new ArrayList<>(works.size());
            for (Future<ModuleAnalysis> future : futures) {
                results.add(await(future));
            }
            return results;
        }
    }

    private static ModuleAnalysis await(Future<ModuleAnalysis> future) {
        try {
            return future.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("resume module analysis interrupted", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("resume module analysis failed", cause);
        }
    }

    public AnalysisView view(Long resumeId) {
        ResumeAnalysis entity = analyses.findByResumeId(resumeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        List<Block> blocks = read(entity.getBlocksJson(),
                objectMapper.getTypeFactory().constructCollectionType(List.class, Block.class));
        List<ModuleView> modules = read(entity.getModulesJson(),
                objectMapper.getTypeFactory().constructCollectionType(List.class, ModuleView.class));
        return new AnalysisView(resumeId, entity.getModelName(), entity.getMarkdown(), blocks,
                widenAnchors(modules, blocks), stats(modules), entity.getCreatedAt());
    }

    /**
     * 历史分析结果里的 anchor 还是"半句锚点"，读出来时按句读补齐。
     *
     * <p>文本视图的高亮是拿 anchor 去 mark 的，不补的话同一句话在文本视图里仍是半截，
     * 和原版视图（已经扩到整句）对不上。补的是区间，不动 quote——quote 是模型给的证据原文。</p>
     */
    private static List<ModuleView> widenAnchors(List<ModuleView> modules, List<Block> blocks) {
        Map<String, String> texts = new HashMap<>();
        for (Block block : blocks) {
            texts.put(block.id(), block.text());
        }
        List<ModuleView> widened = new ArrayList<>(modules.size());
        for (ModuleView module : modules) {
            List<PointView> points = new ArrayList<>(module.points().size());
            for (PointView point : module.points()) {
                points.add(widen(point, texts));
            }
            widened.add(new ModuleView(module.type(), module.title(), module.summary(),
                    module.blockIds(), points));
        }
        return widened;
    }

    private static PointView widen(PointView point, Map<String, String> texts) {
        Anchor anchor = point.anchor();
        if (anchor == null || anchor.blockId() == null) {
            return point;
        }
        String text = texts.get(anchor.blockId());
        if (text == null || anchor.start() < 0 || anchor.end() > text.length()
                || anchor.start() >= anchor.end()) {
            return point;
        }
        int[] span = SentenceSpan.bounds(text, anchor.start(), anchor.end());
        // 往左补的时候别把手机号/邮箱一起点亮：批注本来就跳过联系方式
        if (span[0] < anchor.start()
                && CONTACT.matcher(text.substring(span[0], anchor.start())).find()) {
            span[0] = anchor.start();
        }
        if (span[0] == anchor.start() && span[1] == anchor.end()) {
            return point;
        }
        return new PointView(point.id(), point.title(), point.quote(),
                new Anchor(anchor.blockId(), span[0], span[1]), point.level(), point.comment(),
                point.improvements(), point.questions(),
                point.focus() != null ? point.focus() : focusOf(point.comment(), text));
    }

    /**
     * 从点评里找出"被点名的那个说法"，用作局部标注的落点。
     *
     * <p>面试官点评时习惯把问题词加引号："'双Topic'的具体职责未明确"、"“理解”无法体现深度"、
     * "尤其“SQL 调优”缺少指标"。这类批注要说的是<b>这个词</b>不专业，而不是整条经历不够，
     * 所以把引号里的词抽出来做焦点；抽不出来（例如"只有职责罗列"）就按整条处理。</p>
     *
     * <p>只接受能在原文里找到的词：找不到说明模型在概括、不是在引用，宁可整句高亮。</p>
     */
    private static String focusOf(String comment, String text) {
        if (comment == null || comment.isBlank() || text == null || text.isEmpty()) {
            return null;
        }
        String loose = loose(text);
        Matcher matcher = QUOTED.matcher(comment);
        while (matcher.find()) {
            String term = matcher.group(1).trim();
            if (term.length() < 2 || term.length() > 24) {
                continue;
            }
            if (loose.contains(loose(term))) {
                return term;
            }
        }
        return null;
    }

    /** 去掉所有空白并转小写：只用于"这段词在不在原文里"的判断。 */
    private static String loose(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!Character.isWhitespace(ch)) {
                builder.append(Character.toLowerCase(ch));
            }
        }
        return builder.toString();
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
                // quote 是 10-60 字的锚点，经常停在半个词上；定位到之后把区间扩到整句，
                // 文本视图里的 mark 才和原版视图里的高亮是同一段话
                int[] span = SentenceSpan.bounds(block.text(), index, index + quote.length());
                return new Anchor(block.id(), span[0], span[1]);
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

    /** 一个待点评的模块：草稿 + 该模块的拼合文本（拼合是纯字符串操作，放串行阶段做没意义）。 */
    private record ModuleWork(ModuleSplitAgent.ModuleDraft draft, String text) {
    }

    /** 并发阶段只产出"模块 + 模型给的原始小点"，锚点定位和编号留到串行阶段。 */
    private record ModuleAnalysis(ModuleWork work, List<PointAnalysisAgent.PointDraft> points) {
    }

    /**
     * 一条批注。
     *
     * @param focus 非空表示这条批注针对的是"某个说法不专业"：页面上只框这几个字
     *              （"双Topic"、"理解"），quote 那句只作为上下文
     */
    public record PointView(String id, String title, String quote, Anchor anchor, String level,
                            String comment, List<String> improvements, List<String> questions,
                            String focus) {
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
