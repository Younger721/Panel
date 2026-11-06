package com.nova.interview.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nova.interview.domain.model.Resume;
import com.nova.interview.domain.repository.ResumeRepository;
import com.nova.interview.infrastructure.pdf.PdfPreview;
import com.nova.knowledge.application.KnowledgeObjectStore;
import com.nova.shared.error.BusinessException;
import com.nova.shared.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
                    List.of(), List.of(), Map.of());
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
                                            Math.round(line.fontSize() * 10f) / 10f, line.font(), line.bold()))
                                    .toList()))
                    .toList();
            return new TextLayerView(true, null, pages, sections, edits);
        } catch (RuntimeException ex) {
            log.warn("Resume text layer unavailable: id={}, error={}", resumeId, ex.toString());
            return new TextLayerView(false, "无法读取 PDF 文本层，已切换为结构化编辑器",
                    List.of(), List.of(), Map.of());
        }
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

    public byte[] pageImage(Long resumeId, int pageNumber, Float scale) {        Resume resume = require(resumeId);
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
            byte[] png = pdf.renderPage(pageNumber, effective);
            store.put(key, png, "image/png");
            return png;
        } catch (RuntimeException ex) {
            log.warn("Resume page render failed: id={}, page={}, error={}", resumeId, pageNumber, ex.toString());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    /**
     * 批量定位：一次请求把当前分析里所有 quote 映射到页面矩形。
     * 返回的 map 只包含定位成功的点，前端对缺失的点做降级排版。
     */
    public Map<String, HighlightView> locate(Long resumeId, List<QuoteRequest> quotes) {
        Resume resume = require(resumeId);
        if (!isPdf(resume) || quotes == null || quotes.isEmpty()) {
            return Map.of();
        }
        byte[] bytes = original(resume);
        Map<String, HighlightView> result = new LinkedHashMap<>();
        try (PdfPreview pdf = PdfPreview.open(bytes)) {
            for (QuoteRequest quote : quotes) {
                if (quote == null || quote.pointId() == null || quote.pointId().isBlank()) {
                    continue;
                }
                PdfPreview.Highlight highlight = pdf.locate(quote.quote())
                        .or(() -> pdf.locate(quote.fallback()))
                        .orElse(null);
                if (highlight == null) {
                    continue;
                }
                List<RectView> rects = highlight.rects().stream()
                        .map(rect -> new RectView(round(rect.x()), round(rect.y()),
                                round(rect.width()), round(rect.height())))
                        .toList();
                result.put(quote.pointId(), new HighlightView(highlight.page(), rects));
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

    private byte[] original(Resume resume) {
        byte[] bytes = store.read(resume.getSourceObjectKey());
        if (bytes == null || bytes.length == 0) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return bytes;
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
                                List<SectionView> sections, Map<String, String> edits) {
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
                           float fontSize, String font, boolean bold) {
    }

    public record RectView(float x, float y, float width, float height) {
    }

    public record HighlightView(int page, List<RectView> rects) {
    }

    public record QuoteRequest(String pointId, String quote, String fallback) {
    }

    public record LocateRequest(List<QuoteRequest> quotes) {
    }
}
