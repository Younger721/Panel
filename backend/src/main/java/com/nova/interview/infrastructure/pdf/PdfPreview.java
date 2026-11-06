package com.nova.interview.infrastructure.pdf;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 原版简历预览：把 PDF 逐页渲染成位图，并保留"字符 → 页面坐标"。
 *
 * <p>为什么不用 Markdown/HTML 重排：PDF 里的分栏、右对齐的日期、字号与装饰线
 * 是排版结果而不是文本语义，重排一定会失真。这里直接渲染原页面，批注只需在
 * 页面上画框，看起来就和用户上传的文件一模一样。</p>
 *
 * <p>坐标统一用 PDF 点（1/72 英寸），原点在页面左上角，且已经过页面旋转修正，
 * 与 {@link PDFRenderer} 渲染出的位图方向一致。前端按百分比缩放即可。</p>
 */
public final class PdfPreview implements AutoCloseable {

    /** 短于这个长度的片段不足以定位一句话，宁可不高亮也不乱标。 */
    private static final int MIN_PARTIAL = 8;

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
    public List<LineBox> textLines() {
        if (lineBoxes == null) {
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
     * 在原文里定位一句话。
     *
     * <p>模型引用的 quote 常与 PDF 抽出的文字差几个空格或标点，所以按"全角归一 +
     * 去空白"后的字符串匹配；整句找不到时退化为最长前缀（至少 {@link #MIN_PARTIAL}
     * 个字），再退化为去掉前导符号。都失败返回空，由前端把批注卡按顺序排下去。</p>
     */
    public Optional<Highlight> locate(String quote) {
        String needle = normalize(quote).text();
        if (needle.isEmpty()) {
            return Optional.empty();
        }
        List<PageText> texts = pageTexts();

        for (PageText page : texts) {
            int index = page.text().indexOf(needle);
            if (index >= 0) {
                return Optional.of(highlight(page, index, needle.length()));
            }
        }

        int prefix = longestPrefix(texts, needle);
        if (prefix >= MIN_PARTIAL) {
            String part = needle.substring(0, prefix);
            for (PageText page : texts) {
                int index = page.text().indexOf(part);
                if (index >= 0) {
                    return Optional.of(highlight(page, index, prefix));
                }
            }
        }

        // 模型偶尔会在引用前带一个序号或符号（"1. "、"• "），整体右移再试
        for (int skip = 1; skip <= Math.min(6, needle.length() - MIN_PARTIAL); skip++) {
            String part = needle.substring(skip);
            for (PageText page : texts) {
                int index = page.text().indexOf(part);
                if (index >= 0) {
                    return Optional.of(highlight(page, index, part.length()));
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
            result.add(new PageText(info.index(), info.width(), info.height(), normalized.text(), charRects));
        }
        return result;
    }

    private static Highlight highlight(PageText page, int start, int length) {
        List<Rect> matched = new ArrayList<>(length);
        int end = Math.min(page.charRects().size(), start + length);
        for (int i = start; i < end; i++) {
            matched.add(page.charRects().get(i));
        }
        return new Highlight(page.index(), merge(matched));
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
     * 分隔条的矩形：整行宽、比标题行高一点点，正好把原件那条底色带盖住，
     * 于是所有板块标题看起来是同一种横条（样式由前端全局配置）。
     */
    private static Part headingPart(PageInfo page, LineBox heading) {
        float left = 24f;
        float right = page.width() - 24f;
        float top = Math.max(0f, heading.y() - 7f);
        float bottom = Math.min(page.height(), heading.y() + heading.height() + 7f);
        return new Part(page.index(), left, top, right - left, bottom - top);
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
        // 照片、校徽、色块都在同一片区域，整块盖掉会把这些装饰一起抹了
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
            case '\u3002' -> '.';
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

    public record PageText(int index, float width, float height, String text, List<Rect> charRects) {
    }

    public record Highlight(int page, List<Rect> rects) {
    }

    /**
     * 一段可原位编辑的文字：坐标原点为页面左上角，单位 PDF 点。
     *
     * @param size 字号（pt），前端按它渲染覆盖层，才能和底图对得上
     */
    public record LineBox(int page, String text, float x, float y, float width, float height, float size) {
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
            }
            if (right <= left || size <= 0f) {
                return;
            }
            byPageLines.computeIfAbsent(page, key -> new ArrayList<>())
                    .add(new LineBox(page, value, left, top, right - left, bottom - top, size));
        }

        List<TextPosition> positionsOf(int pageNumber) {
            return byPage.getOrDefault(pageNumber, List.of());
        }

        List<LineBox> linesOf(int pageNumber) {
            return byPageLines.getOrDefault(pageNumber, List.of());
        }
    }
}
