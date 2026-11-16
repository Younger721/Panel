package com.panel.interview.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.panel.interview.domain.model.Resume;
import com.panel.interview.domain.repository.ResumeRepository;
import com.panel.interview.infrastructure.pdf.PdfPreview;
import com.panel.knowledge.application.KnowledgeObjectStore;
import com.panel.shared.error.BusinessException;
import com.panel.shared.error.ErrorCode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 把「原位编辑」的结果落成一份真 PDF。
 *
 * <p>做法和编辑器一致：只动改过的板块——先用白底把这些行盖掉，再用原件里那个嵌入字体、
 * 同样的字号和行距把新文字写回去；没改的页面、图片、排版一个字节都不碰，所以导出的
 * 文件看着就是原件，只是文字变了。</p>
 */
@Service
public class ResumePdfExportService {

    private static final Logger log = LoggerFactory.getLogger(ResumePdfExportService.class);

    /** 行距兜底：原件没量出相邻行间距时，按字号乘这个系数排。 */
    private static final float FALLBACK_LEADING = 1.55f;

    /** 白底盖住的量：横向多盖一点，免得旧字边缘留着半像素。 */
    private static final float ERASE_PAD = 0.8f;

    /** 嵌入字体加载失败时的系统中文字体兜底（Windows / 常见 Linux 路径）。 */
    private static final List<String> FALLBACK_FONTS = List.of(
            "C:/Windows/Fonts/simhei.ttf",
            "C:/Windows/Fonts/simsunb.ttf",
            "C:/Windows/Fonts/Deng.ttf",
            "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
            "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc");

    private final ResumeRepository resumes;
    private final KnowledgeObjectStore store;
    private final ObjectMapper objectMapper;

    public ResumePdfExportService(ResumeRepository resumes, KnowledgeObjectStore store, ObjectMapper objectMapper) {
        this.resumes = resumes;
        this.store = store;
        this.objectMapper = objectMapper;
    }

    /** @return 改完之后可直接下载的 PDF 字节 */
    public byte[] export(Long resumeId) {
        Resume resume = resumes.findById(resumeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!isPdf(resume)) {
            // 不挡的话 Loader.loadPDF 对 docx/txt 只会抛 IOException，
            // 最后变成 500「服务器内部错误」——用户完全看不出是自己传了非 PDF。
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "只有 PDF 简历支持导出改后的原件");
        }
        byte[] source = original(resume);
        Map<String, String> edits = edits(resume);
        try (PdfPreview preview = PdfPreview.open(source); PDDocument doc = Loader.loadPDF(source)) {
            if (!edits.isEmpty()) {
                FontBook fonts = new FontBook(doc, preview);
                for (PdfPreview.SectionBox section : preview.sections()) {
                    String html = edits.get(String.valueOf(section.index()));
                    if (html == null || html.isBlank()) {
                        continue;
                    }
                    rewrite(doc, preview, fonts, section, plainText(html));
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException ex) {
            log.warn("Resume pdf export failed: id={}, error={}", resumeId, ex.toString());
            throw new IllegalStateException("resume pdf export failed", ex);
        }
    }

    /** 导出用的文件名：原件名去掉扩展名 + -edited.pdf。 */
    public String exportFileName(Long resumeId) {
        String name = resumes.findById(resumeId).map(Resume::getFileName).orElse("resume");
        String base = name.replaceAll("\\.[^.]+$", "");
        return base.isBlank() ? "resume-edited.pdf" : base + "-edited.pdf";
    }

    // ── 回写一块板块 ───────────────────────────────────────────────

    private void rewrite(PDDocument doc, PdfPreview preview, FontBook fonts,
                         PdfPreview.SectionBox section, String edited) throws IOException {
        List<PdfPreview.LineBox> lines = section.lines();
        if (lines.isEmpty()) {
            return;
        }
        // 没改就别动：编辑器把未改动的板块也带上来了，白盖一遍会丢字
        String original = String.join("\n", lines.stream().map(PdfPreview.LineBox::text).toList());
        if (normalize(original).equals(normalize(edited))) {
            return;
        }

        Map<Integer, List<PdfPreview.LineBox>> byPage = new LinkedHashMap<>();
        for (PdfPreview.LineBox line : lines) {
            byPage.computeIfAbsent(line.page(), key -> new ArrayList<>()).add(line);
        }

        List<String> pending = new ArrayList<>(List.of(edited.split("\n", -1)));
        for (Map.Entry<Integer, List<PdfPreview.LineBox>> entry : byPage.entrySet()) {
            int pageNumber = entry.getKey();
            List<PdfPreview.LineBox> pageLines = entry.getValue();
            if (pageNumber < 1 || pageNumber > doc.getNumberOfPages()) {
                continue;
            }
            PDPage page = doc.getPage(pageNumber - 1);
            float pageHeight = page.getMediaBox().getHeight();
            float left = Float.MAX_VALUE;
            float right = -Float.MAX_VALUE;
            for (PdfPreview.LineBox line : pageLines) {
                left = Math.min(left, line.x());
                right = Math.max(right, line.x() + line.width());
            }
            float width = Math.max(24f, right - left);
            float leading = leadingOf(pageLines);

            try (PDPageContentStream cs = new PDPageContentStream(
                    doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                // 1) 白底盖掉旧行
                cs.setNonStrokingColor(1f, 1f, 1f);
                for (PdfPreview.LineBox line : pageLines) {
                    float h = line.height() > 1f ? line.height() : line.size() * 1.2f;
                    cs.addRect(line.x() - ERASE_PAD,
                            pageHeight - (line.y() + h) - ERASE_PAD * 0.5f,
                            line.width() + ERASE_PAD * 2f,
                            h + ERASE_PAD);
                }
                cs.fill();

                // 2) 一行对一行：新文本第 i 行就落在原第 i 行的位置，字距行距天然不动。
                //    行数对不上时，多出来的行按原行距继续往下排。
                cs.setNonStrokingColor(0.09f, 0.11f, 0.16f);
                PdfPreview.LineBox anchor = pageLines.get(pageLines.size() - 1);
                float cursor = anchor.y();
                for (int i = 0; i < pending.size(); i++) {
                    String text = pending.get(i) == null ? "" : pending.get(i).strip();
                    PdfPreview.LineBox slot = i < pageLines.size() ? pageLines.get(i) : null;
                    float size = slot != null ? textSize(slot) : textSize(anchor);
                    float x = slot != null ? slot.x() : pageLines.get(0).x();
                    if (slot == null) {
                        cursor += leading;
                    }
                    float top = slot != null ? slot.y() : cursor;
                    float baseline = top + size * 0.92f;
                    float y = pageHeight - baseline;
                    if (y < size * 0.5f || y > pageHeight) {
                        continue;
                    }
                    PDFont font = fonts.forText(slot != null ? slot : anchor, text);
                    if (font == null) {
                        continue;
                    }
                    write(cs, font, size, x, y, fit(text, font, size, width));
                }
                pending.clear();
            }
        }
    }

    /** 原件量不出字号时的兜底。 */
    private static float textSize(PdfPreview.LineBox line) {
        float size = line.size() > 1f ? line.size() : line.fontSize();
        return size > 1f ? size : 10f;
    }

    /**
     * 一行里塞不下的字按宽度裁掉（塞不下通常说明用户把整段粘了进来，
     * 这时候宁可截断也不让文字压到别的板块上）。
     */
    private static String fit(String text, PDFont font, float size, float width) {
        if (text.isEmpty() || width(text, font, size) <= width) {
            return text;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            String glyph = new String(Character.toChars(codePoint));
            if (width(out + glyph, font, size) > width) {
                break;
            }
            out.append(glyph);
            i += Character.charCount(codePoint);
        }
        return out.toString();
    }

    /** 逐段折行；写不进去的字（字体没这个字形）直接跳过，不让整块导出失败。 */
    private static List<String> wrap(List<String> paragraphs, PDFont font, float size, float width) {
        List<String> out = new ArrayList<>();
        for (String paragraph : paragraphs) {
            out.add(paragraph == null ? "" : paragraph.strip());
        }
        return out;
    }

    /** 这行字能不能被这个字体完整写出来（缺字形就会在 showText 时炸）。 */
    private static boolean canEncode(PDFont font, String text) {
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            try {
                font.encode(new String(Character.toChars(codePoint)));
            } catch (Exception ex) {
                return false;
            }
        }
        return true;
    }

    private static float width(String text, PDFont font, float size) {
        try {
            return font.getStringWidth(text) / 1000f * size;
        } catch (IOException | IllegalArgumentException ex) {
            return text.length() * size;
        }
    }

    private static void write(PDPageContentStream cs, PDFont font, float size,
                              float x, float y, String text) throws IOException {
        // 先把字体放不下的字剔掉再开文本块：showText 抛异常时文本块没闭合，
        // 下一次 beginText() 会报 "Nested beginText() calls are not allowed."
        StringBuilder safe = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            String glyph = new String(Character.toChars(codePoint));
            i += Character.charCount(codePoint);
            try {
                font.encode(glyph);
                safe.append(glyph);
            } catch (Exception ignored) {
                // 这个字形字体里没有，跳过
            }
        }
        if (safe.length() == 0) {
            return;
        }
        cs.beginText();
        try {
            cs.setFont(font, size);
            cs.newLineAtOffset(x, y);
            cs.showText(safe.toString());
        } finally {
            cs.endText();
        }
    }

    /** 相邻行的基线间距中位数；量不出来就按字号兜底。 */
    private static float leadingOf(List<PdfPreview.LineBox> lines) {
        List<Float> deltas = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            float delta = lines.get(i).y() - lines.get(i - 1).y();
            if (delta > 0.5f && delta < lines.get(i).size() * 4f) {
                deltas.add(delta);
            }
        }
        if (deltas.isEmpty()) {
            float size = lines.get(0).size() > 1f ? lines.get(0).size() : 10f;
            return size * FALLBACK_LEADING;
        }
        deltas.sort(Float::compare);
        return deltas.get(deltas.size() / 2);
    }

    // ── 字体 ─────────────────────────────────────────────────────

    /** 把原件里嵌入的字体按 fontKey 建成可写对象；缺了就退系统字体。 */
    private static final class FontBook {

        private final PDDocument doc;
        private final Map<String, byte[]> embedded = new LinkedHashMap<>();
        private final Map<String, String> formats = new LinkedHashMap<>();
        private final Map<String, PDFont> cache = new LinkedHashMap<>();
        private PDFont fallback;

        private FontBook(PDDocument doc, PdfPreview preview) {
            this.doc = doc;
            for (PdfPreview.EmbeddedFont font : preview.fonts()) {
                if (font.key() != null && font.data() != null && font.data().length > 0) {
                    embedded.put(font.key(), font.data());
                    formats.put(font.key(), font.format() == null ? "" : font.format().toLowerCase(Locale.ROOT));
                }
            }
        }

        private PDFont of(PdfPreview.LineBox line) {
            String key = line.fontKey() == null ? "" : line.fontKey();
            PDFont cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
            PDFont font = loadEmbedded(key);
            if (font == null) {
                font = systemFallback();
            }
            if (font != null) {
                cache.put(key, font);
            }
            return font;
        }

        /**
         * 为一段文字挑字体：优先原件里那个嵌入字体（字形和纸上一模一样），
         * 它写不出来（比如子集里没有数字或新加的字）才退到系统中文字体。
         */
        private PDFont forText(PdfPreview.LineBox line, String text) {
            PDFont preferred = of(line);
            if (preferred != null && canEncode(preferred, text)) {
                return preferred;
            }
            PDFont system = systemFallback();
            if (system != null) {
                return system;
            }
            return preferred;
        }

        private PDFont loadEmbedded(String key) {
            byte[] data = embedded.get(key);
            if (data == null) {
                return null;
            }
            // CFF(OpenType) 的嵌入字体 PDType0Font 未必吃得下，试一次不行就退系统字体
            if (formats.getOrDefault(key, "").contains("opentype")) {
                return null;
            }
            try {
                return PDType0Font.load(doc, new ByteArrayInputStream(data), true);
            } catch (Exception ex) {
                log.debug("embedded font unusable for export: key={}, error={}", key, ex.toString());
                return null;
            }
        }

        private PDFont systemFallback() {
            if (fallback != null) {
                return fallback;
            }
            for (String path : FALLBACK_FONTS) {
                if (!path.toLowerCase(Locale.ROOT).endsWith(".ttf")) {
                    continue;
                }
                if (!Files.isReadable(Path.of(path))) {
                    continue;
                }
                try {
                    fallback = PDType0Font.load(doc, Files.newInputStream(Path.of(path)), true);
                    return fallback;
                } catch (Exception ex) {
                    log.debug("system font unusable: path={}, error={}", path, ex.toString());
                }
            }
            return null;
        }
    }

    // ── 输入 ─────────────────────────────────────────────────────

    /** 和预览那边同一套判断：MIME 或文件名后缀任一命中就算 PDF。 */
    private static boolean isPdf(Resume resume) {
        String type = resume.getSourceContentType() == null ? "" : resume.getSourceContentType().toLowerCase(Locale.ROOT);
        String name = resume.getFileName() == null ? "" : resume.getFileName().toLowerCase(Locale.ROOT);
        return type.contains("pdf") || name.endsWith(".pdf");
    }

    private byte[] original(Resume resume) {
        byte[] bytes = store.read(resume.getSourceObjectKey());
        if (bytes == null || bytes.length == 0) {
            bytes = store.read(resume.getObjectKey());
        }
        if (bytes == null || bytes.length == 0) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return bytes;
    }

    private Map<String, String> edits(Resume resume) {
        String json = resume.getEditLayer();
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception ex) {
            log.warn("Resume edit layer unreadable for export: id={}, error={}", resume.getId(), ex.toString());
            return Map.of();
        }
    }

    /** 编辑器存的是富文本：去掉标签、块级标签转换行，实体解码，压掉多余空白。 */
    static String plainText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String text = html
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(div|p|li|h[1-6]|tr)>", "\n")
                .replaceAll("(?s)<[^>]+>", "")
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&amp;", "&");
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() && (out.length() == 0 || out.charAt(out.length() - 1) == '\n')) {
                continue;
            }
            out.append(trimmed).append('\n');
        }
        return out.toString().stripTrailing();
    }

    private static String normalize(String text) {
        Set<String> lines = new LinkedHashSet<>();
        for (String line : text.split("\n")) {
            String trimmed = line.replace('\u00a0', ' ').strip();
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }
        return String.join("\n", lines);
    }
}
