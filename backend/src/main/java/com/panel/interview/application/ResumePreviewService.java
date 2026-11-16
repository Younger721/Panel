package com.panel.interview.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.panel.interview.domain.model.Resume;
import com.panel.interview.domain.repository.ResumeRepository;
import com.panel.interview.infrastructure.pdf.PdfPreview;
import com.panel.knowledge.application.KnowledgeObjectStore;
import com.panel.shared.error.BusinessException;
import com.panel.shared.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 简历"原版预览"：把上传的 PDF 按页渲染出来，并在原文上定位批注句子。
 *
 * <p>批注页以前是把解析出的文本重新排版，和用户上传的简历长得不一样；
 * 这里直接渲染原文件，版式与原件 100% 一致。批注仍然按 quote 定位，
 * 只是定位结果从"字符区间"变成"页面上的矩形"。</p>
 *
 * <p>渲染结果按内容哈希缓存在对象存储里：同一份文件第二次打开不再重画，
 * 换一份文件也不会串图。非 PDF 返回 available=false，前端自动退回文本视图。</p>
 */
@Service
public class ResumePreviewService {

    private static final Logger log = LoggerFactory.getLogger(ResumePreviewService.class);

    private static final float DEFAULT_SCALE = 2.5f;
    private static final float MAX_SCALE = 4f;
    /** 一次定位请求最多处理多少条批注引用。 */
    private static final int MAX_LOCATE_QUOTES = 200;

    private final ResumeRepository resumes;
    private final KnowledgeObjectStore store;
    private final ObjectMapper objectMapper;

    public ResumePreviewService(ResumeRepository resumes, KnowledgeObjectStore store, ObjectMapper objectMapper) {
        this.resumes = resumes;
        this.store = store;
        this.objectMapper = objectMapper;
    }

    /** 只描述页面尺寸；图片由 {@link #pageImage} 单独取，避免一次性传几 MB。 */
    public PreviewView preview(Long resumeId) {
        Resume resume = require(resumeId);
        if (!isPdf(resume)) {
            return new PreviewView(false, "原版预览目前支持 PDF，这份文件将显示文本视图", List.of());
        }
        byte[] bytes = original(resume);
        try (PdfPreview pdf = PdfPreview.open(bytes)) {
            List<PageView> pages = new ArrayList<>(pdf.pageCount());
            for (PdfPreview.PageInfo info : pdf.pages()) {
                pages.add(new PageView(info.index(), info.width(), info.height()));
            }
            return new PreviewView(true, null, pages);
        } catch (RuntimeException ex) {
            log.warn("Resume preview unavailable: id={}, error={}", resumeId, ex.toString());
            return new PreviewView(false, "无法渲染原版预览，已切换为文本视图", List.of());
        }
    }

    /**
     * 文字图层：每页的页面尺寸 + 逐行片段的文本与坐标。
     *
     * <p>前端把页面位图当底图、把这些片段定位叠上去，就得到"和 PDF 一模一样、
     * 但点一块就能整块改"的编辑器。非 PDF 返回空图层，前端退回结构化编辑器。</p>
     */
    public TextLayerView textLayer(Long resumeId) {
        Resume resume = require(resumeId);
        if (!isPdf(resume)) {
            return new TextLayerView(false, "非 PDF 文件没有原版版面，这里用结构化编辑器改文字",
                    List.of(), List.of(), List.of(), Map.of());
        }
        byte[] bytes = original(resume);
        Map<String, String> edits = storedEdits(resume);
        try (PdfPreview pdf = PdfPreview.open(bytes)) {
            List<PageView> pages = pdf.pages().stream()
                    .map(info -> new PageView(info.index(), info.width(), info.height()))
                    .toList();
            List<SectionView> sections = pdf.sections().stream()
                    .map(section -> new SectionView(section.index(), section.title(),
                            section.heading() == null ? null : partView(section.heading()),
                            section.parts().stream().map(ResumePreviewService::partView).toList(),
                            section.lines().stream()
                                    .map(line -> new LineView(line.page(), line.text(),
                                            round(line.x()), round(line.y()), round(line.width()), round(line.height()),
                                            Math.round(line.size() * 10f) / 10f,
                                            Math.round(line.fontSize() * 10f) / 10f,
                                            line.font(), line.fontKey(), line.bold(),
                                            line.runs().stream()
                                                    .map(run -> new RunView(run.text(), run.font(), run.fontKey(),
                                                            Math.round(run.size() * 10f) / 10f, run.bold()))
                                                    .toList()))
                                    .toList()))
                    .toList();
            List<FontView> fonts = pdf.fonts().stream()
                    .map(font -> new FontView(font.key(), familyOf(font.data()), font.format(), font.data().length))
                    .toList();
            return new TextLayerView(true, null, pages, sections, fonts, edits);
        } catch (RuntimeException ex) {
            log.warn("Resume text layer unavailable: id={}, error={}", resumeId, ex.toString());
            return new TextLayerView(false, "无法读取 PDF 文本层，已切换为结构化编辑器",
                    List.of(), List.of(), List.of(), Map.of());
        }
    }

    /**
     * PDF 里嵌入的某个字体文件的原始字节。
     *
     * <p>编辑框用 {@code @font-face} 把这份字节装进浏览器，字形就和渲染出来的页面完全一致——
     * 光按字体名映射系统字体是不够的：这份简历存的名字是 KaiTi，嵌进去的字形却是宋体味儿的。</p>
     */
    public FontFile fontFile(Long resumeId, String family) {
        Resume resume = require(resumeId);
        if (!isPdf(resume) || family == null || family.isBlank()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        try (PdfPreview pdf = PdfPreview.open(original(resume))) {
            for (PdfPreview.EmbeddedFont font : pdf.fonts()) {
                if (familyOf(font.data()).equals(family)) {
                    return new FontFile(font.data(), contentType(font.format()));
                }
            }
        } catch (RuntimeException ex) {
            log.warn("Resume font unavailable: id={}, family={}, error={}", resumeId, family, ex.toString());
        }
        throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }

    /** 字体家族名按内容哈希取，稳定且不会因为字体顺序变化而串。 */
    private static String familyOf(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(data);
            StringBuilder builder = new StringBuilder("PanelFont_");
            for (int i = 0; i < 4; i++) {
                builder.append(String.format(Locale.ROOT, "%02x", digest[i]));
            }
            return builder.toString();
        } catch (Exception ex) {
            return "PanelFont_" + Math.abs(java.util.Arrays.hashCode(data));
        }
    }

    private static String contentType(String format) {
        return "opentype".equals(format) ? "font/otf" : "font/ttf";
    }

    /** 上一次保存留下的行改动；解析不了就当没有，不能让坏 JSON 把编辑器挡住。 */
    private Map<String, String> storedEdits(Resume resume) {
        String json = resume.getEditLayer();
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception ex) {
            log.warn("Resume edit layer unreadable: id={}, error={}", resume.getId(), ex.toString());
            return Map.of();
        }
    }

    public byte[] pageImage(Long resumeId, int pageNumber, Float scale) {
        Resume resume = require(resumeId);
        if (!isPdf(resume)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        float effective = normalizeScale(scale);
        String key = cacheKey(resume, pageNumber, effective);
        byte[] cached = store.read(key);
        if (cached != null && cached.length > 0) {
            return cached;
        }
        byte[] bytes = original(resume);
        try (PdfPreview pdf = PdfPreview.open(bytes)) {
            // 页码越界是调用方给错了（习惯了 0 起算，或文件换过一版），不是服务端故障。
            // 报 400 并带上合法范围，比让用户对着"服务器内部错误"猜要有用得多。
            if (pageNumber < 1 || pageNumber > pdf.pageCount()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "页码超出范围：这份 PDF 共 " + pdf.pageCount() + " 页（从 1 开始）");
            }
            byte[] png = pdf.renderPage(pageNumber, effective);
            store.put(key, png, "image/png");
            return png;
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("Resume page render failed: id={}, page={}, error={}", resumeId, pageNumber, ex.toString());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    /**
     * 批量定位：一次请求把当前分析里所有 quote 映射到页面矩形。
     * 返回的 map 只包含定位成功的点，前端对缺失的点做降级排版。
     *
     * <p>{@code focus} 非空表示这条批注针对的是"某个说法"，只框那几个字；
     * 为空则按整句框（一句话的经验/成果，本来就该整句一起看）。</p>
     */
    public Map<String, HighlightView> locate(Long resumeId, List<QuoteRequest> quotes) {
        Resume resume = require(resumeId);
        if (!isPdf(resume) || quotes == null || quotes.isEmpty()) {
            return Map.of();
        }
        // 每份 quote 都要在整份 PDF 里搜一遍，几千条就是一次自伤式的 CPU 尖峰。
        // 一份简历的批注点撑死几十个，超出这个量的请求没有正当用途。
        if (quotes.size() > MAX_LOCATE_QUOTES) {
            log.warn("Resume locate request truncated: id={}, quotes={}", resumeId, quotes.size());
            quotes = quotes.subList(0, MAX_LOCATE_QUOTES);
        }
        byte[] bytes = original(resume);
        Map<String, HighlightView> result = new LinkedHashMap<>();
        try (PdfPreview pdf = PdfPreview.open(bytes)) {
            for (QuoteRequest quote : quotes) {
                if (quote == null || quote.pointId() == null || quote.pointId().isBlank()) {
                    continue;
                }
                boolean local = quote.focus() != null && !quote.focus().isBlank();
                // 局部标注先在这条批注自己的句子里找焦点词，找不到才退回全文搜
                var placed = local
                        ? pdf.locate(quote.focus(), quote.quote(), false)
                                .or(() -> pdf.locate(quote.focus(), false))
                        : pdf.locate(quote.quote());
                PdfPreview.Highlight highlight = placed
                        .or(() -> pdf.locate(quote.quote()))
                        .or(() -> pdf.locate(quote.fallback()))
                        .orElse(null);
                if (highlight == null) {
                    continue;
                }
                List<RectView> rects = highlight.rects().stream()
                        .map(rect -> new RectView(round(rect.x()), round(rect.y()),
                                round(rect.width()), round(rect.height())))
                        .toList();
                result.put(quote.pointId(), new HighlightView(highlight.page(), rects,
                        highlight.text(), highlight.focus()));
            }
        } catch (RuntimeException ex) {
            log.warn("Resume locate failed: id={}, error={}", resumeId, ex.toString());
            return Map.of();
        }
        return result;
    }

    private Resume require(Long resumeId) {
        if (resumeId == null || resumeId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return resumes.findById(resumeId).orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /**
     * 读原文件；原版指针坏了就顺手修，别把"重传一次"这种体力活丢给用户。
     *
     * <p>历史上换过桶（nova-ai → panel-ai）、也出现过只更新了 {@code object_key}
     * 而 {@code source_object_key} 留在旧 key 上的行。这类记录的正文指针其实还指着
     * 同一份原文件，能读就直接读，并把原版指针改正——渲染结果和原版编辑器用的是
     * 同一个字节源，不会出现"这次能看、下次又丢"的反复。</p>
     *
     * <p>回退只在两边的文件类型一致时进行：正文指针可能指向编辑保存的
     * {@code edited-*.txt}，拿它冒充 PDF 只会把"原件丢失"变成更难懂的
     * "无法读取 PDF 文本层"。</p>
     */
    private byte[] original(Resume resume) {
        byte[] bytes = readOrNull(resume.getSourceObjectKey());
        if (bytes != null && bytes.length > 0) {
            return bytes;
        }
        String fallbackKey = resume.getObjectKey();
        if (fallbackKey != null
                && !fallbackKey.equals(resume.getSourceObjectKey())
                && isPdf(resume) == fallbackKey.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            byte[] fallback = readOrNull(fallbackKey);
            if (fallback != null && fallback.length > 0) {
                log.info("Resume source key healed from object key: id={}, key={}", resume.getId(), fallbackKey);
                resume.healSource(resume.getFileName(), resume.getSourceContentType(),
                        fallbackKey, resume.getContentHash());
                resumes.save(resume);
                return fallback;
            }
        }
        throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }

    private byte[] readOrNull(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        try {
            return store.read(key);
        } catch (RuntimeException ex) {
            log.warn("Resume object unreadable: key={}, error={}", key, ex.toString());
            return null;
        }
    }

    private static boolean isPdf(Resume resume) {
        String type = resume.getSourceContentType() == null ? "" : resume.getSourceContentType().toLowerCase(Locale.ROOT);
        String name = resume.getFileName() == null ? "" : resume.getFileName().toLowerCase(Locale.ROOT);
        return type.contains("pdf") || name.endsWith(".pdf");
    }

    private static float normalizeScale(Float scale) {
        float value = scale == null ? DEFAULT_SCALE : scale;
        if (!Float.isFinite(value)) {
            value = DEFAULT_SCALE;
        }
        return Math.max(1f, Math.min(MAX_SCALE, value));
    }

    private static String cacheKey(Resume resume, int pageNumber, float scale) {
        return "preview/" + resume.getContentHash() + "/" + String.format(Locale.ROOT, "%.1f", scale)
                + "/page-" + pageNumber + ".png";
    }

    private static float round(float value) {
        return Math.round(value * 10f) / 10f;
    }

    public record PageView(int index, float width, float height) {
    }

    public record PreviewView(boolean available, String reason, List<PageView> pages) {
    }

    /** 可整体编辑的文字层：一块 = 一个板块，跨页的板块有多个 part。 */
    public record TextLayerView(boolean available, String reason, List<PageView> pages,
                                List<SectionView> sections, List<FontView> fonts,
                                Map<String, String> edits) {
    }

    /** 一个内嵌字体：key 与 line.fontKey 对应，family 是给 @font-face 用的稳定名字。 */
    public record FontView(String key, String family, String format, int size) {
    }

    public record FontFile(byte[] data, String contentType) {
    }

    public record SectionView(int index, String title, PartView heading, List<PartView> parts,
                              List<LineView> lines) {
    }

    private static PartView partView(PdfPreview.Part part) {
        return new PartView(part.page(), round(part.x()), round(part.y()), round(part.width()), round(part.height()));
    }

    public record PartView(int page, float x, float y, float width, float height) {
    }

    public record LineView(int page, String text, float x, float y, float width, float height, float size,
                           float fontSize, String font, String fontKey, boolean bold, List<RunView> runs) {
    }

    /** 行内一段同字体的文字（中文楷体 + 数字 Arial 这种）。 */
    public record RunView(String text, String font, String fontKey, float size, boolean bold) {
    }

    public record RectView(float x, float y, float width, float height) {
    }

    /**
     * @param text  整句原文：批注卡拿它当上下文（局部标注时框住的只有 focus 那几个字）
     * @param focus 局部标注框住的字，可为 null
     */
    public record HighlightView(int page, List<RectView> rects, String text, String focus) {
    }

    /**
     * 一条要高亮的批注。
     *
     * @param quote    模型给的锚点（一般 10-60 字）
     * @param fallback 定位失败时的兜底文本（通常是整段）
     * @param focus    只在"某个说法不专业"时才给：要精确定位的那几个字
     */
    public record QuoteRequest(String pointId, String quote, String fallback, String focus) {
    }

    public record LocateRequest(List<QuoteRequest> quotes) {
    }
}
