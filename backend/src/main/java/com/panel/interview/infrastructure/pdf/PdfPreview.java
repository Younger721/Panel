package com.panel.interview.infrastructure.pdf;

import com.panel.shared.text.SentenceSpan;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * 原版简历预览：把 PDF 逐页渲染成位图，并保留"字符 → 页面坐标"。
 * <p>为什么不用 Markdown/HTML 重排：PDF 里的分栏、右对齐的日期、字号与装饰线
 * 是排版结果而不是文本语义，重排一定会失真。这里直接渲染原页面，批注只需在
 *
 * <p>坐标统一用 PDF 点（1/72 英寸），原点在页面左上角，且已经过页面旋转修正，
 * 与 {@link PDFRenderer} 渲染出的位图方向一致。前端按百分比缩放即可。</p>
 */
public final class PdfPreview implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PdfPreview.class);

    /** 短于这个长度的片段不足以定位一句话，宁可不高亮也不乱标。 */
    private static final int MIN_PARTIAL = 8;

    /** 扩到整句时最多跨几次折行：一句话一般也就两行，再多就是下一条了。 */
    private static final int MAX_WRAPS = 1;

    /** 量标题底色带时的渲染倍率：1 点约 2 像素，够量准，又比出图用的 2.5 快。 */
    private static final float BAND_SCALE = 2f;

    /**
     * 只在标题行上下这么宽的一条窗口里找底色带，再远就可能是别的装饰了。
     *
     * <p>不能太小：底色带比标题行框高是常态，正文挤在带子中间，上下各还留一圈；
     * 窗口窄了带子的上下边就落在窗口外，量出来矮一截，盖上之后又是一条边。</p>
     */
    private static final float BAND_WINDOW = 20f;

    /** 量出来的框再向外放一点，把底色带边缘的抗锯齿也盖住。 */
    private static final float BAND_BLEED = 0.8f;

    /** 一行里"不是页面底色"的像素占到页面宽度这么多，才算底色带而不是一行正文。 */
    private static final float BAND_ROW_FILL = 0.45f;

    /** 比这还薄的只能是一条下划线，不是板块标题的底色带。 */
    private static final float MIN_BAND_HEIGHT = 6f;

    /** 判断"和页面底色不同"的容差：再紧一点会把浅色带子当成底色。 */
    private static final int BAND_COLOR_TOLERANCE = 12;

    /**
     * 简历里常见的板块标题。
     *
     * <p>切板块只能靠关键词，不能靠字号——同一份简历里"实习经历"（板块）和
     * "电子招标系统"（项目名）往往一样大一样粗，按字号切一定会切错。</p>
     */
    private static final Set<String> HEADINGS = Set.of(
            "个人信息", "个人介绍", "基本信息", "联系方式", "求职意向",
            "教育背景", "教育经历", "学历背景", "学习经历",
            "实习经历", "工作经历", "实习与工作经历", "工作与实习经历", "实习经验", "工作经验",
            "项目经历", "项目经验", "实习与项目经历", "项目实践",
            "校园经历", "实践经历", "社会实践", "科研经历", "竞赛经历", "培训经历",
            "专业技能", "技能特长", "技能清单", "个人技能", "技术栈", "技能",
            "荣誉证书", "获奖经历", "所获荣誉", "荣誉奖项", "证书",
            "自我评价", "个人评价", "个人优势", "兴趣爱好", "语言能力",
            "论文发表", "科研成果", "开源项目");

    private final PDDocument document;
    private final PDFRenderer renderer;
    private final List<PageInfo> pages;
    private List<PageText> pageTexts;
    private List<LineBox> lineBoxes;
    private List<SectionBox> sections;
    private List<EmbeddedFont> embeddedFonts;
    /** 量标题底色带用的页面渲染图，一页一份 */
    private final Map<Integer, BufferedImage> bandPages = new LinkedHashMap<>();

    private PdfPreview(PDDocument document) {
        this.document = document;
        this.renderer = new PDFRenderer(document);
        this.pages = new ArrayList<>(document.getNumberOfPages());
        for (int i = 0; i < document.getNumberOfPages(); i++) {
            PDPage page = document.getPage(i);
            PDRectangle box = page.getCropBox();
            int rotation = ((page.getRotation() % 360) + 360) % 360;
            boolean landscape = rotation == 90 || rotation == 270;
            float width = landscape ? box.getHeight() : box.getWidth();
            float height = landscape ? box.getWidth() : box.getHeight();
            pages.add(new PageInfo(i + 1, width, height));
        }
    }

    public static PdfPreview open(byte[] bytes) {
        try {
            return new PdfPreview(Loader.loadPDF(bytes));
        } catch (IOException ex) {
            throw new IllegalArgumentException("无法读取 PDF：" + ex.getMessage(), ex);
        }
    }

    public List<PageInfo> pages() {
        return List.copyOf(pages);
    }

    public int pageCount() {
        return pages.size();
    }

    /** @param pageNumber 1 起；scale=2 表示 2 倍分辨率（约 144 DPI） */
    public byte[] renderPage(int pageNumber, float scale) {
        if (pageNumber < 1 || pageNumber > pages.size()) {
            throw new IllegalArgumentException("页码超出范围：" + pageNumber);
        }
        try {
            BufferedImage image = renderer.renderImage(pageNumber - 1, scale, ImageType.RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("渲染 PDF 页面失败", ex);
        }
    }

    /** 每页一段"去空白、全角归一"的正文 + 每个字符的页面矩形。 */
    public List<PageText> pageTexts() {
        if (pageTexts == null) {
            pageTexts = extract();
        }
        return pageTexts;
    }

    /**
     * 逐"行片段"的文本 + 页面矩形，用于把文字叠回渲染图上做原位编辑。
     *
     * <p>粒度就是 PDFTextStripper 的一次回调：同一视觉行的左右两栏会分成两段，
     * 这正好是编辑需要的粒度——改左边不会碰到右边。</p>
     */
    public List<LineBox> textLines() {        if (lineBoxes == null) {
            Collector collector = new Collector();
            try {
                collector.getText(document);
            } catch (IOException ex) {
                throw new IllegalStateException("提取 PDF 文本失败", ex);
            }
            List<LineBox> lines = new ArrayList<>();
            for (PageInfo info : pages) {
                lines.addAll(collector.linesOf(info.index()));
            }
            lineBoxes = List.copyOf(lines);
        }
        return lineBoxes;
    }

    /**
     * PDF 里嵌入的字体文件（子集）。
     *
     * <p>简历排版用的常是系统里装过的中文字体，名字和字形未必对得上（这份"楷体"嵌进去的
     * 其实是宋体味儿的字形），光靠字体名去猜永远是差一点的。把嵌入的那份字节拿出来给前端
     * 当网页字体用，编辑框里的字就和上面那张纸是同一套字形。</p>
     *
     * <p>返回的 key 与 {@link LineBox#fontKey()} 一致（PDF 的 BaseFont 名，带子集前缀）。</p>
     */
    public List<EmbeddedFont> fonts() {
        if (embeddedFonts != null) {
            return embeddedFonts;
        }
        Map<String, EmbeddedFont> found = new LinkedHashMap<>();
        for (PageInfo info : pages) {
            collectFonts(document.getPage(info.index() - 1).getResources(), found, 0);
        }
        embeddedFonts = List.copyOf(found.values());
        return embeddedFonts;
    }

    private void collectFonts(PDResources resources, Map<String, EmbeddedFont> found, int depth) {
        if (resources == null || depth > 4) {
            return;
        }
        for (COSName name : resources.getFontNames()) {
            try {
                PDFont font = resources.getFont(name);
                String key = font.getName();
                if (key == null || found.containsKey(key)) {
                    continue;
                }
                PDFontDescriptor descriptor = font.getFontDescriptor();
                PDStream stream = descriptor == null ? null : fontProgram(descriptor);
                if (stream == null) {
                    continue;
                }
                byte[] data;
                try (InputStream in = stream.createInputStream()) {
                    data = in.readAllBytes();
                }
                if (data.length == 0) {
                    continue;
                }
                found.put(key, new EmbeddedFont(key, streamFormat(stream), data));
            } catch (IOException | RuntimeException ex) {
                log.debug("Skip unreadable PDF font {}: {}", name.getName(), ex.toString());
            }
        }
        for (COSName name : resources.getXObjectNames()) {
            try {
                PDXObject xobject = resources.getXObject(name);
                if (xobject instanceof PDFormXObject form) {
                    collectFonts(form.getResources(), found, depth + 1);
                }
            } catch (IOException | RuntimeException ex) {
                log.debug("Skip unreadable PDF xobject {}: {}", name.getName(), ex.toString());
            }
        }
    }

    private static PDStream fontProgram(PDFontDescriptor descriptor) {
        PDStream stream = descriptor.getFontFile2();
        if (stream != null) {
            return stream;
        }
        stream = descriptor.getFontFile3();
        if (stream != null) {
            return stream;
        }
        return descriptor.getFontFile();
    }

    private static String streamFormat(PDStream stream) {
        String subtype = stream.getCOSObject().getNameAsString(COSName.SUBTYPE);
        if ("OpenType".equals(subtype) || "CIDFontType0C".equals(subtype) || "Type1C".equals(subtype)) {
            return "opentype";
        }
        return "truetype";
    }

    /** 一个嵌入字体：key 是 PDF 里的字体名，data 是原始字体文件。 */
    public record EmbeddedFont(String key, String format, byte[] data) {
    }

    /**
     * 在原文里定位一句话。
     *
     * <p>模型引用的 quote 常与 PDF 抽出的文字差几个空格或标点，所以按"全角归一 +
     * 去空白"后的字符串匹配；整句找不到时退化为最长前缀（至少 {@link #MIN_PARTIAL}
     * 个字），再退化为去掉前导符号。都失败返回空，由前端把批注卡按顺序排下去。</p>
     *
     * <p>命中之后还会把区间扩到句读边界（{@link SentenceSpan}）：模型给的是 10-60 字的
     * 锚点，直接照着画会得到半句话的高亮。扩的时候按字符矩形判断连续性，
     * 不会从正文窜到标题、也不会跨到上一段。</p>
     */
    public Optional<Highlight> locate(String quote) {
        return locate(quote, true);
    }

    /**
     * 定位 + 决定要不要扩成整句。
     *
     * <p>{@code expand=false} 用于"这句话本身没写错，但某个说法不专业"的批注：
     * 只框住那个词（"双Topic"、"理解"），整句仍然作为上下文一起返回，
     * 批注卡就有完整的句子可读，纸上也不会点亮一整行。</p>
     */
    public Optional<Highlight> locate(String quote, boolean expand) {
        return locate(quote, null, expand);
    }

    /**
     * 在 {@code within} 命中范围内找 {@code needle}，找不到再退回全文。
     *
     * <p>局部标注的词往往很短（"理解"、"误报"），全文搜会命中别处的同名词——
     * p13 的"理解"就会跑到上面那条"意图理解"上。所以先在这条批注自己的句子里找。</p>
     */
    public Optional<Highlight> locate(String needle, String within, boolean expand) {
        String target = normalize(needle).text();
        if (target.isEmpty()) {
            return Optional.empty();
        }
        List<PageText> texts = pageTexts();

        if (within != null && !within.isBlank()) {
            String scope = normalize(within).text();
            for (PageText page : texts) {
                int base = page.text().indexOf(scope);
                if (base < 0) {
                    continue;
                }
                int local = page.text().indexOf(target, base);
                if (local >= 0 && local < base + scope.length()) {
                    return Optional.of(highlight(page, local, target.length(), expand));
                }
            }
        }

        for (PageText page : texts) {
            int index = page.text().indexOf(target);
            if (index >= 0) {
                return Optional.of(highlight(page, index, target.length(), expand));
            }
        }

        int prefix = longestPrefix(texts, target);
        if (prefix >= MIN_PARTIAL) {
            String part = target.substring(0, prefix);
            for (PageText page : texts) {
                int index = page.text().indexOf(part);
                if (index >= 0) {
                    return Optional.of(highlight(page, index, prefix, expand));
                }
            }
        }

        // 模型偶尔会在引用前带一个序号或符号（"1. "、"• "），整体右移再试
        for (int skip = 1; skip <= Math.min(6, target.length() - MIN_PARTIAL); skip++) {
            String part = target.substring(skip);
            for (PageText page : texts) {
                int index = page.text().indexOf(part);
                if (index >= 0) {
                    return Optional.of(highlight(page, index, part.length(), expand));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public void close() {
        try {
            document.close();
        } catch (IOException ignored) {
            // 只读预览，关闭失败没有补救动作
        }
    }

    private List<PageText> extract() {
        Collector collector = new Collector();
        try {
            collector.getText(document);
        } catch (IOException ex) {
            throw new IllegalStateException("提取 PDF 文本失败", ex);
        }

        List<PageText> result = new ArrayList<>(pages.size());
        for (PageInfo info : pages) {
            StringBuilder raw = new StringBuilder();
            List<Rect> rawRects = new ArrayList<>();
            for (TextPosition position : collector.positionsOf(info.index())) {
                String unicode = position.getUnicode();
                if (unicode == null || unicode.isEmpty()) {
                    continue;
                }
                float height = Math.abs(safe(position.getHeightDir()));
                float width = Math.max(0.1f, Math.abs(position.getWidthDirAdj()));
                // getYDirAdj() 是基线到页面顶部的距离；只取字高会让 CJK 高亮条偏窄，
                // 按字号撑满一行（上缘约 0.92em、下缘约 0.2em）。
                float font = position.getFontSizeInPt();
                float band = font > 1f ? font : height * 2f;
                float top = position.getYDirAdj() - band * 0.92f;
                Rect rect = new Rect(position.getXDirAdj(), top, width, band * 1.12f);
                for (int i = 0; i < unicode.length(); i++) {
                    raw.append(unicode.charAt(i));
                    rawRects.add(rect);
                }
            }
            Normalized normalized = normalize(raw);
            List<Rect> charRects = new ArrayList<>(normalized.text().length());
            for (int index : normalized.indexes()) {
                charRects.add(rawRects.get(index));
            }
            result.add(new PageText(info.index(), info.width(), info.height(), normalized.text(), charRects,
                    raw.toString(), normalized.indexes()));
        }
        return result;
    }

    /**
     * 命中区间 → 高亮矩形。
     *
     * <p>整句模式（{@code expand=true}）：先把区间按句读扩成完整的句子（模型给的 quote 只到
     * 半个词），再用每个字符的矩形拼出高亮条。两个方向的上限不一样：</p>
     * <ul>
     *   <li>向右允许折行——句子本来就跨行，不跨过去就还是半句；</li>
     *   <li>向左只在同一行内补——简历的条目之间常常没有句号，往左一直走会把上一条也点亮。</li>
     * </ul>
     *
     * <p>局部模式（{@code expand=false}）：只框命中那几个字，但整句仍算出来放进 {@code text}
     * 里当上下文——纸上只点亮"双Topic"，卡片里还能读到完整一句。</p>
     */
    private static Highlight highlight(PageText page, int start, int length, boolean expand) {
        String text = page.text();
        List<Rect> rects = page.charRects();
        int matchedEnd = Math.min(rects.size(), start + length);
        int[] sentence = SentenceSpan.bounds(text, start, matchedEnd,
                sameLineOnly(rects), wrapAllowed(rects));
        int[] span = expand ? sentence : new int[]{start, matchedEnd};
        int from = Math.max(0, Math.min(span[0], Math.max(0, rects.size() - 1)));
        int to = Math.min(rects.size(), Math.max(span[1], Math.max(from + 1, matchedEnd)));
        List<Rect> matched = new ArrayList<>(Math.max(0, to - from));
        for (int i = from; i < to; i++) {
            matched.add(rects.get(i));
        }
        int sentenceFrom = Math.max(0, Math.min(sentence[0], text.length()));
        int sentenceTo = Math.max(sentenceFrom, Math.min(sentence[1], text.length()));
        String context = page.rawText(sentenceFrom, sentenceTo);
        String focus = expand ? null : page.rawText(from, to);
        return new Highlight(page.index(), merge(matched), context, focus);
    }

    /**
     * 向左补句首时的判断：同一行，且中间没有"字段间隔"那种大空当。
     *
     * <p>简历的一行里常并排放好几个字段（"邮 箱：xx@qq.com    毕业年月：2027年6月"），
     * 它们之间没有句号，只有一段空白。只按行判断的话，往左会一路吃到邮箱上去；
     * 所以空白宽度超过半个字高就当作分栏，停下来。</p>
     */
    private static IntPredicate sameLineOnly(List<Rect> rects) {
        return index -> {
            if (index < 0 || index + 1 >= rects.size()) {
                return false;
            }
            Rect a = rects.get(index);
            Rect b = rects.get(index + 1);
            float height = Math.max(a.height(), b.height());
            if (height <= 0f) {
                return false;
            }
            float centreA = a.y() + a.height() / 2f;
            float centreB = b.y() + b.height() / 2f;
            if (Math.abs(centreA - centreB) > height * 0.65f) {
                return false;
            }
            // 归一化把空格删了，所以"字与字之间的空当"直接体现为矩形的横向间隔
            float gap = b.x() - (a.x() + a.width());
            return gap <= height * 0.45f;
        };
    }

    /**
     * 向右补句尾：允许折行，但最多跨一次。
     *
     * <p>句子本来就跨行，不跨过去就还是半句；可简历里的条目之间往往也没有句号，
     * 只按行距判断会一路吃到下一条（"数字人面试官…追问"直接连上"上下文管理…"）。
     * 跨一次折行刚好覆盖常见的"一句话两行"，再往下就是另一个条目了。</p>
     *
     * <p>行距突然变大（段落之间）、字号差三成（正文 → 板块标题）、或者中间有一段
     * 分栏大空当，都不跨。</p>
     */
    private static IntPredicate wrapAllowed(List<Rect> rects) {
        int[] wraps = {0};
        return index -> {
            if (index < 0 || index + 1 >= rects.size()) {
                return false;
            }
            Rect a = rects.get(index);
            Rect b = rects.get(index + 1);
            float height = Math.max(a.height(), b.height());
            if (height <= 0f) {
                return false;
            }
            if (Math.min(a.height(), b.height()) < height * 0.72f) {
                return false;
            }
            float centreA = a.y() + a.height() / 2f;
            float centreB = b.y() + b.height() / 2f;
            if (Math.abs(centreA - centreB) <= height * 0.65f) {
                // 同一行：只有分栏级的大空当才拦
                return b.x() - (a.x() + a.width()) <= height * 4f;
            }
            if (Math.abs(centreA - centreB) > height * 2f) {
                return false;
            }
            wraps[0]++;
            return wraps[0] <= MAX_WRAPS;
        };
    }

    /** 同一行、间距不大的字符合并成一条高亮，避免每个字一个小方块。 */
    private static List<Rect> merge(List<Rect> rects) {
        List<Rect> merged = new ArrayList<>();
        Rect current = null;
        for (Rect rect : rects) {
            if (current == null) {
                current = rect;
            } else if (sameLine(current, rect)) {
                current = current.union(rect);
            } else {
                merged.add(current);
                current = rect;
            }
        }
        if (current != null) {
            merged.add(current);
        }
        return merged;
    }

    private static boolean sameLine(Rect a, Rect b) {
        float height = Math.max(a.height(), b.height());
        if (height <= 0f) {
            return false;
        }
        float centerA = a.y() + a.height() / 2f;
        float centerB = b.y() + b.height() / 2f;
        if (Math.abs(centerA - centerB) > height * 0.65f) {
            return false;
        }
        float gap = b.x() - (a.x() + a.width());
        return gap <= Math.max(3f, height * 2f);
    }

    private static int longestPrefix(List<PageText> texts, String needle) {
        if (needle.length() < MIN_PARTIAL) {
            return 0;
        }
        int low = MIN_PARTIAL - 1;
        int high = needle.length();
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (containsPrefix(texts, needle, middle)) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return low >= MIN_PARTIAL ? low : 0;
    }

    private static boolean containsPrefix(List<PageText> texts, String needle, int length) {
        String part = needle.substring(0, length);
        for (PageText page : texts) {
            if (page.text().contains(part)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按板块切好的文字区域：前端一块一块地编辑（点「实习经历」整块打开编辑框）。
     *
     * <p>切法：用板块标题当分隔符。第一个标题之前的部分算"个人信息"（姓名、电话、
     * 照片旁的那几行）；每个标题到下一个标题之间算一块。没有标题的页面整页算一块，
     * 这样任何简历都至少有一个可编辑的区域。</p>
     *
     * <p><b>标题自己不算正文</b>：它是板块的分隔条（{@link SectionBox#heading()}），
     * 由前端按全局样式画成统一的横条；可编辑的 {@code lines} 从标题下面一行开始。
     * 这样"教育背景"这类标题不会出现在编辑框里被当成正文改，也不会跟着正文一起被重排。</p>
     *
     * <p><b>跨页要接回去</b>：上一页写到一半的板块，下一页开头那几行没有标题，
     * 不能单独成块——直接把这几行并进上一块，块里就有了两个 {@link Part}（两页各一段）。
     * 否则「实习经历」会被切成"正文一块 + 接上页一块"，用户点哪块都改不全。</p>
     */
    public List<SectionBox> sections() {
        if (sections != null) {
            return sections;
        }
        List<LineBox> lines = textLines();
        List<Draft> drafts = new ArrayList<>();
        for (PageInfo page : pages) {
            List<LineBox> pageLines = new ArrayList<>();
            for (LineBox line : lines) {
                if (line.page() == page.index()) {
                    pageLines.add(line);
                }
            }
            if (pageLines.isEmpty()) {
                continue;
            }
            pageLines.sort(Comparator.comparingDouble(LineBox::y).thenComparingDouble(LineBox::x));

            List<Integer> headingAt = new ArrayList<>();
            for (int i = 0; i < pageLines.size(); i++) {
                if (headingOf(pageLines.get(i)) != null) {
                    headingAt.add(i);
                }
            }

            int headEnd = headingAt.isEmpty() ? pageLines.size() : headingAt.get(0);
            if (headEnd > 0) {
                Draft previous = drafts.isEmpty() ? null : drafts.get(drafts.size() - 1);
                if (previous != null) {
                    // 这一页开头没有标题 → 是上一页那一块的续写
                    previous.ranges.add(new Range(page, pageLines.subList(0, headEnd), true));
                } else {
                    drafts.add(new Draft(page.index() == 1 ? "个人信息" : "全部内容",
                            page, null, pageLines.subList(0, headEnd), true));
                }
            }
            for (int i = 0; i < headingAt.size(); i++) {
                int start = headingAt.get(i);
                int end = i + 1 < headingAt.size() ? headingAt.get(i + 1) : pageLines.size();
                LineBox heading = pageLines.get(start);
                List<LineBox> content = pageLines.subList(start + 1, end);
                if (content.isEmpty()) {
                    // 只有标题没有正文：那就没有可编辑区，标题也就当正文留着
                    drafts.add(new Draft(headingOf(heading), page, null, List.of(heading), false));
                } else {
                    drafts.add(new Draft(headingOf(heading), page, heading, content, false));
                }
            }
        }

        List<SectionBox> result = new ArrayList<>();
        int index = 0;
        for (Draft draft : drafts) {
            Part bar = draft.heading == null ? null : headingPart(draft.headingPage, draft.heading);
            List<Part> parts = new ArrayList<>();
            List<LineBox> sectionLines = new ArrayList<>();
            for (Range range : draft.ranges) {
                sectionLines.addAll(range.lines());
                List<LineBox> sorted = new ArrayList<>(range.lines());
                sorted.sort(Comparator.comparingDouble(LineBox::y).thenComparingDouble(LineBox::x));
                // 有标题的块：正文从那条间隔条下面开始，别让标题混进可点区域
                float minTop = bar != null && range.page() == draft.headingPage
                        ? bar.y() + bar.height() + 2f
                        : 0f;
                parts.add(part(range.page(), sorted, range.untitled(), minTop));
            }
            result.add(new SectionBox(index++, draft.title, bar, List.copyOf(parts), List.copyOf(sectionLines)));
        }
        sections = List.copyOf(result);
        return sections;
    }

    /** 一块在一页上占的区域；跨页的板块有多个。 */
    public record Part(int page, float x, float y, float width, float height) {
    }

    private record Range(PageInfo page, List<LineBox> lines, boolean untitled) {
    }

    /** 切块过程中的一块：标题（分隔条）+ 若干段正文（跨页时多段）。 */
    private static final class Draft {
        private final String title;
        private final PageInfo headingPage;
        private final LineBox heading;
        private final List<Range> ranges = new ArrayList<>();

        private Draft(String title, PageInfo page, LineBox heading, List<LineBox> lines, boolean untitled) {
            this.title = title;
            this.headingPage = page;
            this.heading = heading;
            this.ranges.add(new Range(page, lines, untitled));
        }
    }

    /**
     * 分隔条的矩形：前端拿它当"盖住原件标题色块"的横条，所以它必须**比原件的色块大**。
     *
     * <p>以前这里按"整行宽 + 标题行上下各 7 点"硬估，估得偏小就会露馅：
     * 原件那条底色带比估出来的框宽一点，盖上之后左右各剩一条蓝边，
     * 看起来就"横条下面还有一条蓝条"。不同简历的带子宽高各不相同，
     * 猜不出来的，只能从渲染图里量（{@link #bandPart}），量到就取并集。</p>
     */
    private Part headingPart(PageInfo page, LineBox heading) {
        Part guess = guessedHeadingPart(page, heading);
        Part band = bandPart(page, heading, guess);
        if (band == null) {
            return guess;
        }
        float left = Math.max(0f, Math.min(guess.x(), band.x()) - BAND_BLEED);
        float top = Math.max(0f, Math.min(guess.y(), band.y()) - BAND_BLEED);
        float right = Math.min(page.width(),
                Math.max(guess.x() + guess.width(), band.x() + band.width()) + BAND_BLEED);
        float bottom = Math.min(page.height(),
                Math.max(guess.y() + guess.height(), band.y() + band.height()) + BAND_BLEED);
        return new Part(page.index(), left, top, Math.max(0f, right - left), Math.max(0f, bottom - top));
    }

    /** 按标题行估的框：整行宽、上下各留 7 点，作为量不到底色带时的兜底。 */
    private static Part guessedHeadingPart(PageInfo page, LineBox heading) {
        float left = 24f;
        float right = page.width() - 24f;
        float top = Math.max(0f, heading.y() - 7f);
        float bottom = Math.min(page.height(), heading.y() + heading.height() + 7f);
        return new Part(page.index(), left, top, right - left, bottom - top);
    }

    /**
     * 从渲染图里量出标题那条底色带的真实矩形。
     *
     * <p>做法：把标题行上下若干点的一窄条渲染出来，逐行数"不是页面底色"的像素。
     * 底色带是横贯整行的一大片实心色，一行能占到页面宽度的大半；正文只有零星几个字，
     * 所以按"占比"就能把带子和正文分开。量完只在标题那一小段窗口里取，不会越界到别处。</p>
     *
     * <p>量不准（没有底色带、带子太窄、或者窗口里被版头图案糊住）返回 {@code null}，
     * 调用方退回按标题行估的框——宁可估，也不能因为量错把正文盖掉。</p>
     */
    private Part bandPart(PageInfo page, LineBox heading, Part guess) {
        BufferedImage image = bandImage(page);
        if (image == null) {
            return null;
        }
        int width = image.getWidth();
        int height = image.getHeight();
        float px = width / page.width();
        int background = backgroundOf(image);
        // 底色带通常比标题行框高：正文贴着带子中间，上下各还留一圈。
        // 窗口要按行高放宽，不然带子的上下边会落在窗口外面，量出来就矮一截
        int pad = Math.round(Math.max(BAND_WINDOW, heading.height() * 0.6f) * px);
        int windowTop = Math.max(0, Math.round(heading.y() * px) - pad);
        int windowBottom = Math.min(height, Math.round((heading.y() + heading.height()) * px) + pad);
        int rows = windowBottom - windowTop;
        if (rows < 3) {
            return null;
        }

        int[] fill = new int[rows];
        int[] first = new int[rows];
        int[] last = new int[rows];
        int peak = 0;
        for (int row = 0; row < rows; row++) {
            int y = windowTop + row;
            int count = 0;
            int from = -1;
            int to = -1;
            for (int x = 0; x < width; x++) {
                if (differs(image.getRGB(x, y), background)) {
                    count++;
                    if (from < 0) {
                        from = x;
                    }
                    to = x;
                }
            }
            fill[row] = count;
            first[row] = from;
            last[row] = to;
            peak = Math.max(peak, count);
        }
        if (peak <= 0) {
            return null;
        }
        int threshold = Math.max(Math.round(BAND_ROW_FILL * width), Math.round(peak * 0.7f));
        int center = Math.min(rows - 1, Math.max(0,
                Math.round((heading.y() + heading.height() / 2f) * px) - windowTop));
        int seed = -1;
        for (int radius = 0; radius < rows && seed < 0; radius++) {
            if (center - radius >= 0 && fill[center - radius] >= threshold) {
                seed = center - radius;
            } else if (center + radius < rows && fill[center + radius] >= threshold) {
                seed = center + radius;
            }
        }
        if (seed < 0) {
            return null;
        }
        int rowTop = seed;
        while (rowTop - 1 >= 0 && fill[rowTop - 1] >= threshold) {
            rowTop--;
        }
        int rowBottom = seed;
        while (rowBottom + 1 < rows && fill[rowBottom + 1] >= threshold) {
            rowBottom++;
        }
        int minX = Integer.MAX_VALUE;
        int maxX = -1;
        for (int row = rowTop; row <= rowBottom; row++) {
            if (first[row] < 0) {
                continue;
            }
            minX = Math.min(minX, first[row]);
            maxX = Math.max(maxX, last[row]);
        }
        if (maxX <= minX) {
            return null;
        }
        float bandHeight = (rowBottom - rowTop + 1) / px;
        float bandWidth = (maxX + 1 - minX) / px;
        // 太窄太薄的（一行下划线、正文里的色块）不算底色带；
        // 比估的框高太多说明量到的是一大片背景或版头图案，也不可信
        if (bandWidth < page.width() * 0.35f || bandHeight < MIN_BAND_HEIGHT
                || bandHeight > guess.height() * 2.5f) {
            return null;
        }
        return new Part(page.index(), minX / px, (windowTop + rowTop) / px, bandWidth, bandHeight);
    }

    /** 量底色带用的整页渲染图：一页只画一次，多个标题共用。 */
    private BufferedImage bandImage(PageInfo page) {
        BufferedImage cached = bandPages.get(page.index());
        if (cached != null) {
            return cached;
        }
        try {
            BufferedImage image = renderer.renderImage(page.index() - 1, BAND_SCALE, ImageType.RGB);
            bandPages.put(page.index(), image);
            return image;
        } catch (IOException | RuntimeException ex) {
            log.debug("Cannot render page {} for heading band: {}", page.index(), ex.toString());
            return null;
        }
    }

    /** 页面底色（通常是白）：整页里出现最多的颜色，量底色带时用它当"空"的基准。 */
    private static int backgroundOf(BufferedImage image) {
        Map<Integer, Integer> histogram = new LinkedHashMap<>();
        int step = 4;
        for (int y = 0; y < image.getHeight(); y += step) {
            for (int x = 0; x < image.getWidth(); x += step) {
                histogram.merge(image.getRGB(x, y) & 0xFFFFFF, 1, Integer::sum);
            }
        }
        return histogram.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(0xFFFFFF);
    }

    private static boolean differs(int rgb, int background) {
        int value = rgb & 0xFFFFFF;
        return Math.abs(((value >> 16) & 0xFF) - ((background >> 16) & 0xFF)) > BAND_COLOR_TOLERANCE
                || Math.abs(((value >> 8) & 0xFF) - ((background >> 8) & 0xFF)) > BAND_COLOR_TOLERANCE
                || Math.abs((value & 0xFF) - (background & 0xFF)) > BAND_COLOR_TOLERANCE;
    }

    private static Part part(PageInfo page, List<LineBox> lines, boolean untitled, float minTop) {
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (LineBox line : lines) {
            minX = Math.min(minX, line.x());
            minY = Math.min(minY, line.y());
            maxX = Math.max(maxX, line.x() + line.width());
            maxY = Math.max(maxY, line.y() + line.height());
        }
        // 没有标题的那段（页首姓名/联系方式、跨页续写）只框住文字本身：
        // 照片、校徽、色块都在同一片区域，整块盖掉会把这些装饰一起抹掉
        float left = untitled ? Math.max(0f, minX - 6f) : 24f;
        float right = untitled
                ? Math.min(page.width(), maxX + 6f)
                : Math.max(page.width() - 24f, maxX + 6f);
        float top = Math.max(minTop, Math.max(0f, minY - 6f));
        float bottom = Math.min(page.height(), maxY + 8f);
        return new Part(page.index(), left, top, right - left, bottom - top);
    }

    /** 标题行返回归一化后的标题文字，否则返回 null。 */
    private static String headingOf(LineBox line) {
        String cleaned = line.text().replaceAll("\\s+", "").replace("：", "").replace(":", "");
        if (cleaned.isEmpty() || cleaned.length() > 12) {
            return null;
        }
        String key = cleaned.toLowerCase(Locale.ROOT);
        return HEADINGS.contains(key) ? cleaned : null;
    }

    private static float safe(float value) {
        return Float.isFinite(value) && value > 0.5f ? value : 1f;
    }

    /** 图标字体的私用区字符：看不见也没法编辑，别让它占着一个可点的框。 */
    private static boolean isDecorative(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean icon = (c >= '\uE000' && c <= '\uF8FF') || Character.isISOControl(c);
            boolean blank = Character.isWhitespace(c) || c == '\u00A0';
            if (!icon && !blank) {
                return false;
            }
        }
        return true;
    }

    private static Normalized normalize(CharSequence raw) {
        if (raw == null) {
            return new Normalized("", new int[0]);
        }
        StringBuilder text = new StringBuilder(raw.length());
        List<Integer> indexes = new ArrayList<>(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char value = raw.charAt(i);
            if (isBlank(value)) {
                continue;
            }
            text.append(fold(value));
            indexes.add(i);
        }
        int[] mapped = new int[indexes.size()];
        for (int i = 0; i < mapped.length; i++) {
            mapped[i] = indexes.get(i);
        }
        return new Normalized(text.toString(), mapped);
    }

    private static boolean isBlank(char value) {
        return value <= ' '
                || Character.isWhitespace(value)
                || value == '\u00A0'
                || value == '\u200B'
                || value == '\u3000';
    }

    /** 全半角与常见标点归一：匹配时只关心字形，不关心排版用的标点变体。 */
    private static char fold(char value) {
        if (value >= '\uFF01' && value <= '\uFF5E') {
            return (char) (value - 0xFEE0);
        }
        return switch (value) {
            // 句号不折成 '.'：折了之后和"3.9"、"qq.com"里的点分不开，
            // 句读边界就找不回来了（这里留着 '\u3002'，扩展整句时才认得出来）
            case '\u3001' -> ',';
            case '\u2018', '\u2019' -> '\'';
            case '\u201C', '\u201D' -> '"';
            case '\u2013', '\u2014' -> '-';
            default -> Character.toLowerCase(value);
        };
    }

    private record Normalized(String text, int[] indexes) {
    }

    public record PageInfo(int index, float width, float height) {
    }

    public record PageText(int index, float width, float height, String text, List<Rect> charRects,
                           String raw, int[] rawIndexes) {

        /** 把归一化区间换回原文：批注卡要显示"Spring Boot"，而不是"springboot"。 */
        public String rawText(int from, int to) {
            if (from < 0 || to <= from || to > rawIndexes.length) {
                return "";
            }
            int start = rawIndexes[from];
            int end = rawIndexes[to - 1] + 1;
            if (start < 0 || end > raw.length() || start >= end) {
                return "";
            }
            return raw.substring(start, end);
        }
    }

    /**
     * 一处高亮。
     *
     * @param page  页码
     * @param rects 页面上要涂的矩形（局部模式下只有那几个字）
     * @param text  这些矩形所在的那一整句原文，批注卡拿它当上下文
     * @param focus 局部模式下被框住的那几个字；整句模式为 null
     */
    public record Highlight(int page, List<Rect> rects, String text, String focus) {
    }

    /**
     * 一段可原位编辑的文字：坐标原点为页面左上角，单位 PDF 点。
     *
     * @param size 字号（pt），前端按它渲染覆盖层，才能和底图对得上
     */
    /**
     * 一条视觉行。
     *
     * @param size     版面用的字高（行框/高亮条的高度按它算）
     * @param fontSize 实际渲染字号（按全角字宽量的），编辑框还原字体大小用这个
     * @param font     去掉子集前缀的字体名（KaiTi / ArialMT），兜底用
     * @param fontKey  PDF 里的原样字体名（BAAAAA+KaiTi），对应 {@link #fonts()} 里的嵌入字体
     * @param runs     行内按字体切开的段；整行同一个字体时为空
     */
    public record LineBox(int page, String text, float x, float y, float width, float height, float size,
                          float fontSize, String font, String fontKey, boolean bold, List<Run> runs) {
    }

    /** 行内一段同字体的文字。 */
    public record Run(String text, String font, String fontKey, float size, boolean bold) {
    }

    /**
     * 一块可整体编辑的区域（板块）。
     *
     * @param index   全局顺序，前端用它当"这块被改过"的键
     * @param title   板块标题，如「实习经历」；页首那块是「个人信息」
     * @param heading 标题那条分隔条的矩形，没有标题的块为 {@code null}
     * @param parts   正文在页面上占的区域，跨页的板块有多段
     * @param lines   正文行（不含标题行）
     */
    public record SectionBox(int index, String title, Part heading, List<Part> parts, List<LineBox> lines) {
    }

    public record Rect(float x, float y, float width, float height) {

        public Rect union(Rect other) {
            float left = Math.min(x, other.x);
            float top = Math.min(y, other.y);
            float right = Math.max(x + width, other.x + other.width);
            float bottom = Math.max(y + height, other.y + other.height);
            return new Rect(left, top, right - left, bottom - top);
        }
    }

    /** 按"行"收集文字位置：PDFTextStripper 每次回调就是一行的一段。 */
    private static final class Collector extends PDFTextStripper {

        private final Map<Integer, List<TextPosition>> byPage = new LinkedHashMap<>();
        private final Map<Integer, List<LineBox>> byPageLines = new LinkedHashMap<>();

        Collector() {
            super();
        }

        @Override
        protected void startPage(PDPage page) throws IOException {
            byPage.computeIfAbsent(getCurrentPageNo(), key -> new ArrayList<>());
            super.startPage(page);
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) {
            if (textPositions == null || textPositions.isEmpty()) {
                return;
            }
            int page = getCurrentPageNo();
            byPage.computeIfAbsent(page, key -> new ArrayList<>()).addAll(textPositions);

            String value = text == null ? "" : text.replace('\r', ' ').replace('\n', ' ').strip();
            if (value.isEmpty() || isDecorative(value)) {
                return;
            }
            float left = Float.MAX_VALUE;
            float top = Float.MAX_VALUE;
            float right = -Float.MAX_VALUE;
            float bottom = -Float.MAX_VALUE;
            float size = 0f;
            List<Float> wideAdvances = new ArrayList<>();
            for (TextPosition position : textPositions) {
                String unicode = position.getUnicode();
                if (unicode == null || unicode.isEmpty()) {
                    continue;
                }
                float width = Math.max(0.1f, Math.abs(position.getWidthDirAdj()));
                float font = position.getFontSizeInPt();
                float band = font > 1f ? font : Math.abs(safe(position.getHeightDir())) * 2f;
                float y0 = position.getYDirAdj() - band * 0.92f;
                left = Math.min(left, position.getXDirAdj());
                right = Math.max(right, position.getXDirAdj() + width);
                top = Math.min(top, y0);
                bottom = Math.max(bottom, y0 + band * 1.12f);
                size = Math.max(size, band);
                if (isWide(unicode.charAt(0))) {
                    wideAdvances.add(width / unicode.length());
                }
            }
            if (right <= left || size <= 0f) {
                return;
            }
            // 真实字号按「全角字的字宽」量：不少简历 PDF 的 Tf 值和实际渲染大小对不上
            // （文字矩阵里带缩放），直接拿 getFontSizeInPt 会把编辑框里的字放大一圈
            float fontSize = median(wideAdvances);
            if (fontSize <= 0f) {
                fontSize = size;
            }
            // 字体名/粗体跟着这一行走：编辑框要还原成简历上的样子，不能一律用系统默认字体。
            // 混排的行（"AI智能视频标签…"）按字最多的那个字体算，否则前半截 Arial 会把整行带偏
            Map<String, Integer> weight = new LinkedHashMap<>();
            Map<String, PDFont> fonts = new LinkedHashMap<>();
            for (TextPosition position : textPositions) {
                String unicode = position.getUnicode();
                PDFont used = position.getFont();
                if (unicode == null || unicode.isEmpty() || used == null) {
                    continue;
                }
                String key = used.getName() == null ? "" : used.getName();
                fonts.putIfAbsent(key, used);
                weight.merge(key, unicode.length(), Integer::sum);
            }
            PDFont dominant = null;
            int best = -1;
            for (Map.Entry<String, Integer> entry : weight.entrySet()) {
                if (entry.getValue() > best) {
                    best = entry.getValue();
                    dominant = fonts.get(entry.getKey());
                }
            }
            // 混排行里有中文就以中文那个字体为准：英文单词多了，按字数算会把整行判成 Arial，
            // 中文再退化成系统无衬线，和纸上的楷体对不上
            for (TextPosition position : textPositions) {
                String unicode = position.getUnicode();
                if (unicode != null && !unicode.isEmpty() && isIdeograph(unicode.charAt(0))
                        && position.getFont() != null) {
                    dominant = position.getFont();
                    break;
                }
            }
            String fontName = fontName(dominant);
            String fontKey = dominant == null || dominant.getName() == null ? "" : dominant.getName();
            boolean bold = isBold(dominant);
            List<Run> runs = runs(textPositions, fonts, size, fontSize, fontName, fontKey, bold);
            byPageLines.computeIfAbsent(page, key -> new ArrayList<>())
                    .add(new LineBox(page, value, left, top, right - left, bottom - top,
                            size, fontSize, fontName, fontKey, bold, runs));
        }

        /**
         * 一行里按字体切成若干段：中文用楷体、数字和英文用 Arial，原样保留。
         *
         * <p>整行只留一个字体的话，正文里夹的数字和英文会被迫用中文字体渲染，
         * 和纸面一比就能看出来。</p>
         */
        private List<Run> runs(List<TextPosition> positions, Map<String, PDFont> fonts,
                               float size, float fontSize, String fallbackFont, String fallbackKey, boolean fallbackBold) {
            List<Run> runs = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            String currentKey = null;
            for (TextPosition position : positions) {
                String unicode = position.getUnicode();
                if (unicode == null || unicode.isEmpty()) {
                    continue;
                }
                String key = position.getFont() == null || position.getFont().getName() == null
                        ? "" : position.getFont().getName();
                if (currentKey != null && !currentKey.equals(key)) {
                    runs.add(runOf(text, currentKey, fonts, fontSize, fallbackFont, fallbackKey, fallbackBold));
                    text = new StringBuilder();
                }
                currentKey = key;
                text.append(unicode);
            }
            if (text.length() > 0) {
                runs.add(runOf(text, currentKey, fonts, fontSize, fallbackFont, fallbackKey, fallbackBold));
            }
            // 全部段落合并成一个字体时没必要多套 span，返回空让前端退回整行样式
            boolean sameFont = runs.stream().map(Run::fontKey).distinct().count() <= 1;
            return sameFont ? List.of() : runs;
        }

        private Run runOf(StringBuilder text, String key, Map<String, PDFont> fonts, float fontSize,
                          String fallbackFont, String fallbackKey, boolean fallbackBold) {
            String value = text.toString();
            PDFont font = key == null ? null : fonts.get(key);
            if (font == null) {
                return new Run(value, fallbackFont, fallbackKey, fontSize, fallbackBold);
            }
            return new Run(value, fontName(font), key, fontSize, isBold(font));
        }

        @Override
        protected void endDocument(PDDocument document) throws IOException {
            super.endDocument(document);
            // 纯英文/数字的行量不出全角字宽，用全篇中文行量出来的比例补一下，
            // 否则同一份简历里英文那几行会明显比中文大
            List<Float> ratios = new ArrayList<>();
            for (List<LineBox> lines : byPageLines.values()) {
                for (LineBox line : lines) {
                    if (line.fontSize() > 0f && line.fontSize() < line.size() - 0.5f) {
                        ratios.add(line.fontSize() / line.size());
                    }
                }
            }
            float ratio = median(ratios);
            if (ratio <= 0f) {
                return;
            }
            for (Map.Entry<Integer, List<LineBox>> entry : byPageLines.entrySet()) {
                List<LineBox> fixed = new ArrayList<>(entry.getValue().size());
                for (LineBox line : entry.getValue()) {
                    if (line.fontSize() < line.size() - 0.5f) {
                        fixed.add(line);
                    } else {
                        fixed.add(new LineBox(line.page(), line.text(), line.x(), line.y(), line.width(),
                                line.height(), line.size(), Math.round(line.size() * ratio * 10f) / 10f,
                                line.font(), line.fontKey(), line.bold(), line.runs()));
                    }
                }
                entry.setValue(fixed);
            }
        }

        /** 真正的汉字（不含全角标点），用来挑"中文那个字体"。 */
        private static boolean isIdeograph(char value) {
            return (value >= 0x4E00 && value <= 0x9FFF) || (value >= 0x3400 && value <= 0x4DBF);
        }

        /** 全角字符（中文、中文标点、全角符号）——它的字宽就是字号。 */
        private static boolean isWide(char value) {            return (value >= 0x2E80 && value <= 0x9FFF)
                    || (value >= 0xF900 && value <= 0xFAFF)
                    || (value >= 0xFE30 && value <= 0xFE4F)
                    || (value >= 0xFF00 && value <= 0xFF60);
        }

        private static float median(List<Float> values) {
            if (values.isEmpty()) {
                return 0f;
            }
            List<Float> sorted = new ArrayList<>(values);
            sorted.sort(Float::compare);
            return sorted.get(sorted.size() / 2);
        }

        /** 去掉子集前缀（ABCDEF+）和样式后缀，只留字体本名，前端才好映射成 CSS family。 */
        private static String fontName(PDFont font) {
            if (font == null) {
                return null;
            }
            String name = font.getName();
            if (name == null || name.isBlank()) {
                return null;
            }
            int plus = name.indexOf('+');
            if (plus >= 0 && plus < 8) {
                name = name.substring(plus + 1);
            }
            int comma = name.indexOf(',');
            if (comma > 0) {
                name = name.substring(0, comma);
            }
            name = name.replace('-', ' ').replace('_', ' ').trim();
            return name.isEmpty() ? null : name;
        }

        private static boolean isBold(PDFont font) {
            if (font == null) {
                return false;
            }
            String name = font.getName() == null ? "" : font.getName().toLowerCase(Locale.ROOT);
            if (name.contains("bold") || name.contains("black") || name.contains("heavy") || name.contains("semibold")) {
                return true;
            }
            PDFontDescriptor descriptor = font.getFontDescriptor();
            if (descriptor == null) {
                return false;
            }
            return descriptor.getFontWeight() >= 600f || descriptor.isForceBold();
        }

        List<TextPosition> positionsOf(int pageNumber) {
            return byPage.getOrDefault(pageNumber, List.of());
        }

        List<LineBox> linesOf(int pageNumber) {
            return byPageLines.getOrDefault(pageNumber, List.of());
        }
    }
}
