package com.panel.interview.infrastructure.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证"原版预览"的两件核心事情：页面能渲染成图片；quote 定位出来的矩形确实压在文字上。
 * 坐标为 PDF 点、原点左上，和前端百分比叠框直接对应。
 */
class PdfPreviewTest {

    @Test
    void rendersPageAndLocatesQuote() throws Exception {
        byte[] pdf = simplePdf();
        try (PdfPreview preview = PdfPreview.open(pdf)) {
            assertEquals(1, preview.pageCount());
            PdfPreview.PageInfo page = preview.pages().get(0);
            assertEquals(300f, page.width(), 0.5f);
            assertEquals(300f, page.height(), 0.5f);

            Optional<PdfPreview.Highlight> located = preview.locate("Hello Resume");
            assertTrue(located.isPresent(), "quote 应当能定位");
            PdfPreview.Rect rect = located.get().rects().get(0);
            assertEquals(50f, rect.x(), 2f);
            // 基线在 200（距底），页面高 300 → 文字上边约在 88
            assertTrue(rect.y() > 70f && rect.y() < 110f, "rect top: " + rect.y());
            assertTrue(rect.width() > 40f, "rect width: " + rect.width());
            assertTrue(rect.height() >= 8f, "rect height: " + rect.height());

            byte[] png = preview.renderPage(1, 2f);
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
            assertEquals(600, image.getWidth());
            assertEquals(600, image.getHeight());

            int ink = 0;
            int top = Math.max(0, (int) (rect.y() * 2));
            int bottom = Math.min(image.getHeight(), (int) ((rect.y() + rect.height()) * 2));
            int left = Math.max(0, (int) (rect.x() * 2));
            int right = Math.min(image.getWidth(), (int) ((rect.x() + rect.width()) * 2));
            for (int y = top; y < bottom; y++) {
                for (int x = left; x < right; x++) {
                    if ((image.getRGB(x, y) & 0xFFFFFF) < 0x888888) {
                        ink++;
                    }
                }
            }
            assertTrue(ink > 20, "定位矩形内应当有文字墨迹，实际 " + ink);
        }
    }

    @Test
    void toleratesSpacingAndPunctuationDifferences() throws Exception {
        byte[] pdf = chineseResumePdf();
        try (PdfPreview preview = PdfPreview.open(pdf)) {
            Optional<PdfPreview.Highlight> exact =
                    preview.locate("2023.09 - 2027.06 广州理工学院 软件工程（本科）");
            assertTrue(exact.isPresent(), "整句应当能定位");

            // 模型引用常常多处一个空格、少一个分号
            Optional<PdfPreview.Highlight> tolerant =
                    preview.locate("专业成绩： GPA 3.9/5（专业前5%）； 主修课程： 数据结构与算法 数据库系统");
            assertTrue(tolerant.isPresent(), "空白/标点差异应当能容忍");

            // 长句只命中前半段时，退化到最长前缀，也应当有高亮
            Optional<PdfPreview.Highlight> partial =
                    preview.locate("作为学校学生代表（学校前0.5%）赴澳门大学交流，获荣誉证书这一段原文里并没有");
            assertTrue(partial.isPresent(), "前缀匹配应当命中");
        }
    }

    /**
     * 文字图层：每行片段都要有能在页面上"叠回去"的坐标和字号。
     *
     * <p>这是 PDF 原位编辑的地基——坐标偏一点，覆盖层就和底图对不上；
     * 字号缺失，改完的字大小和原文不一致，一眼就看出是贴上去的。</p>
     */
    @Test
    void exposesEditableTextLinesWithPageCoordinates() throws Exception {
        byte[] pdf = chineseResumePdf();
        try (PdfPreview preview = PdfPreview.open(pdf)) {
            PdfPreview.PageInfo page = preview.pages().get(0);
            List<PdfPreview.LineBox> lines = preview.textLines();
            assertTrue(lines.size() >= 10, "应当按行切出文字片段，实际 " + lines.size());
            assertEquals(1, lines.stream().map(PdfPreview.LineBox::page).distinct().count());

            PdfPreview.LineBox header = lines.stream()
                    .filter(line -> line.text().contains("教育背景"))
                    .findFirst()
                    .orElseThrow();
            assertEquals(12f, header.size(), 0.6f, "字号应当来自 PDF 本身");
            assertTrue(header.x() > 40f && header.x() < 60f, "x: " + header.x());
            // A4 高 842，测试里基线写在 700（距底）→ 距顶约 130
            assertTrue(header.y() > 110f && header.y() < 150f, "y: " + header.y());
            assertTrue(header.width() > 20f, "width: " + header.width());
            assertTrue(header.height() >= header.size(), "行高至少要容得下字号");

            for (PdfPreview.LineBox line : lines) {
                assertTrue(line.x() >= 0f && line.x() < page.width(), "x 越界: " + line.x());
                assertTrue(line.y() >= 0f && line.y() < page.height(), "y 越界: " + line.y());
                assertTrue(line.x() + line.width() <= page.width() + 1f, "右边界越界: " + line.text());
                assertTrue(line.y() + line.height() <= page.height() + 1f, "下边界越界: " + line.text());
                assertTrue(!line.text().isBlank(), "空行不该出现在图层里");
            }

            // 同一视觉行的左右两段应当是两条，改左边不会把右边糊掉
            assertTrue(lines.stream().anyMatch(line -> line.text().contains("性") && line.text().contains("男")));
        }
    }

    /**
     * 板块切分：标题行（教育背景 / 实习经历 …）是分界，页首无标题的那块算「个人信息」。
     *
     * <p>前端是「点哪块改哪块」，切错了就会出现"改实习经历把教育背景一起吃掉"这种事故，
     * 所以这里把边界、标题、矩形范围都钉住。</p>
     */
    @Test
    void splitsPageIntoEditableSections() throws Exception {
        byte[] pdf = chineseResumePdf();
        try (PdfPreview preview = PdfPreview.open(pdf)) {
            PdfPreview.PageInfo page = preview.pages().get(0);
            List<PdfPreview.SectionBox> sections = preview.sections();
            List<String> titles = sections.stream().map(PdfPreview.SectionBox::title).toList();
            assertEquals(List.of("个人信息", "教育背景", "实习与项目经历"), titles);

            int index = 0;
            for (PdfPreview.SectionBox section : sections) {
                assertEquals(index++, section.index(), "序号要连续，前端拿它当键");
                assertEquals(1, section.parts().size(), "单页里的板块只有一段");
                PdfPreview.Part part = section.parts().get(0);
                assertEquals(1, part.page());
                assertTrue(part.width() > 100f, "板块宽度: " + part.width());
                assertTrue(part.height() > 10f, "板块高度: " + part.height());
                assertTrue(part.x() >= 0f && part.x() + part.width() <= page.width() + 1f, "横向越界");
                assertTrue(part.y() >= 0f && part.y() + part.height() <= page.height() + 1f, "纵向越界");
                assertTrue(!section.lines().isEmpty(), "板块里必须有文字");
            }

            // 三块要按页面上下的顺序排，且互不重叠
            for (int i = 1; i < sections.size(); i++) {
                PdfPreview.Part previous = sections.get(i - 1).parts().get(0);
                PdfPreview.Part current = sections.get(i).parts().get(0);
                assertTrue(current.y() >= previous.y() + previous.height() - 1f,
                        "板块应当自上而下不重叠: " + sections.get(i - 1).title() + " / " + sections.get(i).title());
            }

            // 标题自己是分隔条，不算正文：标题行留在 heading 里，正文从下一行开始
            PdfPreview.SectionBox education = sections.get(1);
            assertTrue(education.heading() != null, "有标题的板块必须有分隔条矩形");
            assertTrue(education.heading().width() > page.width() - 60f, "分隔条要占整行宽度");
            assertTrue(education.heading().y() < education.parts().get(0).y(), "分隔条在正文上方");
            assertTrue(education.lines().stream().noneMatch(line -> line.text().contains("教育背景")),
                    "标题不该混进可编辑正文");
            assertTrue(education.lines().get(0).text().contains("2023.09"), "正文第一行是学段");

            // 页首那块没有标题，只框文字，别把版头装饰一起盖住
            PdfPreview.SectionBox header = sections.get(0);
            assertEquals("个人信息", header.title());
            assertTrue(header.heading() == null, "页首那块没有分隔条");
            assertTrue(header.parts().get(0).width() < page.width() - 40f,
                    "页首块的宽度应当贴合文字: " + header.parts().get(0).width());
        }
    }

    /**
     * 跨页的板块要接回去：上一页写到一半，下一页开头那几行没有标题，不能单独成块。
     *
     * <p>否则用户点「实习经历」只看到一半内容，另一半变成了莫名其妙的「接上页」——
     * 这是实际使用中第一个被发现的问题。</p>
     */
    @Test
    void mergesCrossPageContinuationIntoPreviousSection() throws Exception {
        byte[] pdf = twoPageResumePdf();
        try (PdfPreview preview = PdfPreview.open(pdf)) {
            List<PdfPreview.SectionBox> sections = preview.sections();
            List<String> titles = sections.stream().map(PdfPreview.SectionBox::title).toList();
            assertEquals(List.of("个人信息", "实习经历", "项目经验"), titles,
                    "第二页开头那两行应当并回「实习经历」，而不是单独成块");

            PdfPreview.SectionBox internship = sections.get(1);
            assertEquals(2, internship.parts().size(), "跨页的板块应当有两段矩形");
            assertEquals(1, internship.parts().get(0).page());
            assertEquals(2, internship.parts().get(1).page());
            assertTrue(internship.lines().stream().anyMatch(line -> line.text().contains("第二页的续写")),
                    "续写的行必须在这一块里");
            assertTrue(internship.lines().stream().noneMatch(line -> line.text().contains("实习经历")),
                    "标题是分隔条，不该出现在正文里");

            // 续写那段是"没有标题的"区域，只框文字，不按整行宽度框
            PdfPreview.Part tail = internship.parts().get(1);
            assertTrue(tail.width() < preview.pages().get(1).width() - 40f, "续写段宽度: " + tail.width());
        }
    }

    /**
     * 标题那条底色带比"整行宽 + 上下 7 点"更大时，分隔条要跟着长大把它盖住。
     *
     * <p>真简历里这条带子各家画得都不一样：有的从页边 19.6 点起、有的比正文行高厚一圈。
     * 只按标题行硬估就会小一圈，盖上之后原件那条带子会从边上露出来——
     * 用户看到的就是"间隔横条旁边/下面还有一条蓝边"。</p>
     */
    @Test
    void headingBarCoversTheOriginalColorBand() throws Exception {
        byte[] pdf = bandedResumePdf();
        try (PdfPreview preview = PdfPreview.open(pdf)) {
            PdfPreview.SectionBox education = preview.sections().stream()
                    .filter(section -> "教育背景".equals(section.title()))
                    .findFirst()
                    .orElseThrow();
            PdfPreview.Part bar = education.heading();
            assertTrue(bar != null, "有标题的板块必须有分隔条矩形");
            // 测试里那条带子画在 x 19.6 → 600、距页顶 96 → 132 点
            assertTrue(bar.x() <= 19.6f, "左边要盖住带子: " + bar.x());
            assertTrue(bar.x() + bar.width() >= 600f, "右边要盖住带子: " + (bar.x() + bar.width()));
            assertTrue(bar.y() <= 96f, "上边要盖住带子: " + bar.y());
            assertTrue(bar.y() + bar.height() >= 132f, "下边要盖住带子: " + (bar.y() + bar.height()));
            // 正文不能被盖住：可编辑区从带子下面才开始
            assertTrue(education.parts().get(0).y() > 132f,
                    "正文要在带子下面: " + education.parts().get(0).y());
        }
    }

    /**
     * 高亮要覆盖"一整句"，而不是模型给的那半截锚点。
     *
     * <p>quote 是 10-60 字的锚点，经常停在半个词上（"…对象关系复杂"），照它画出来的高亮
     * 语义被切断；扩到句读之后，跨行的那半句也要带进来。反过来，句子已经完整、
     * 下一行是板块标题时，不能把标题也点亮。</p>
     */
    @Test
    void highlightCoversTheWholeSentence() throws Exception {
        byte[] pdf = chineseResumePdf();
        try (PdfPreview preview = PdfPreview.open(pdf)) {
            Optional<PdfPreview.Highlight> partial = preview.locate(
                    "标注任务全生命周期管理：针对平台、目录、设备等多类型标注对象关系复杂");
            assertTrue(partial.isPresent(), "半句锚点应当能定位");
            PdfPreview.Highlight expanded = partial.get();
            assertEquals("标注任务全生命周期管理：针对平台、目录、设备等多类型标注对象关系复杂、"
                            + "任务状态分散的问题，基于 Spring Boot 和 MyBatis-Plus 实现任务创建、启动、编辑、"
                            + "禁用、删除及状态流转。",
                    expanded.text(), "要补到句号为止，跨行也要带进来，且保留原文大小写与空格");
            assertEquals(2, expanded.rects().size(), "跨行的一句话应当画成两条高亮条");
            assertTrue(expanded.rects().get(1).y() > expanded.rects().get(0).y(),
                    "第二条要在下一行");

            Optional<PdfPreview.Highlight> complete =
                    preview.locate("作为学校学生代表（学校前0.5%）赴澳门大学交流，获荣誉证书");
            assertTrue(complete.isPresent());
            assertEquals("作为学校学生代表（学校前0.5%）赴澳门大学交流，获荣誉证书",
                    complete.get().text(), "下一行是板块标题，不能顺着点亮");
            assertEquals(1, complete.get().rects().size());
        }
    }

    /**
     * 局部标注：只框"被点名的那个说法"，但要把整句作为上下文带回来。
     *
     * <p>批注有时候不是在否定整条经历，而是在说某个词不专业（"'双Topic'职责不明"）。
     * 这时候整句高亮反而看不懂在指哪儿，框住那几个字最直接；卡片靠 {@code text} 补上下文。</p>
     */
    @Test
    void localHighlightKeepsTheSentenceAsContext() throws Exception {
        byte[] pdf = chineseResumePdf();
        try (PdfPreview preview = PdfPreview.open(pdf)) {
            Optional<PdfPreview.Highlight> local =
                    preview.locate("MyBatis-Plus", "基于 Spring Boot 和 MyBatis-Plus 实现任务创建", false);
            assertTrue(local.isPresent(), "局部标注应当能定位");
            assertEquals("MyBatis-Plus", local.get().focus(), "框住的应当只是这个词");
            assertTrue(local.get().text().endsWith("状态流转。"), "上下文要是完整一句: " + local.get().text());
            assertEquals(1, local.get().rects().size(), "同一个词只该有一条高亮条");
            float localWidth = local.get().rects().get(0).width();

            Optional<PdfPreview.Highlight> whole = preview.locate("MyBatis-Plus");
            assertTrue(whole.isPresent());
            assertNull(whole.get().focus(), "整句模式不该有焦点词");
            assertTrue(whole.get().rects().get(0).width() > localWidth * 3,
                    "整句高亮要明显宽于一个词: " + whole.get().rects().get(0).width() + " vs " + localWidth);
        }
    }

    /**
     * 短词（"理解"、"误报"）必须落在<b>这条批注自己的句子</b>里。
     *
     * <p>全文搜会命中上面别处的同名词：p13 批"“理解”体现不出深度"，
     * 结果框到了另一条的"意图理解"上，批注和原文就对不上了。</p>
     */
    @Test
    void shortFocusStaysInsideItsOwnSentence() throws Exception {
        byte[] pdf = chineseResumePdf();
        try (PdfPreview preview = PdfPreview.open(pdf)) {
            Optional<PdfPreview.Highlight> scoped =
                    preview.locate("数据库", "专业成绩：GPA 3.9/5（专业前5%）；主修课程：数据结构与算法、数据库系统", false);
            assertTrue(scoped.isPresent());
            assertEquals("数据库", scoped.get().focus());
            assertTrue(scoped.get().text().contains("主修课程"), "要落在主修课程那句上: " + scoped.get().text());
        }
    }

    @Test
    void rendersChineseSampleForVisualInspection() throws Exception {        Path font = Path.of("C:\\Windows\\Fonts\\simhei.ttf");
        Assumptions.assumeTrue(Files.exists(font), "需要 Windows 中文字体");
        byte[] pdf = chineseResumePdf();
        try (PdfPreview preview = PdfPreview.open(pdf)) {
            byte[] png = preview.renderPage(1, 2f);
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));

            // 把定位矩形画到副本上，肉眼确认高亮和文字是对齐的
            Graphics2D graphics = image.createGraphics();
            graphics.setColor(new Color(250, 204, 21, 110));
            graphics.setStroke(new BasicStroke(1f));
            for (String quote : List.of(
                    "2023.09 - 2027.06 广州理工学院 软件工程（本科）",
                    "专业成绩：GPA 3.9/5（专业前5%）",
                    "作为学校学生代表（学校前0.5%）赴澳门大学交流")) {
                preview.locate(quote).ifPresent(highlight -> {
                    for (PdfPreview.Rect rect : highlight.rects()) {
                        int x = Math.round(rect.x() * 2);
                        int y = Math.round(rect.y() * 2);
                        int w = Math.round(rect.width() * 2);
                        int h = Math.round(rect.height() * 2);
                        graphics.fillRect(x, y, w, h);
                        graphics.drawRect(x, y, w, h);
                    }
                });
            }
            graphics.dispose();

            Path output = Path.of("target", "preview-sample.png");
            Files.createDirectories(output.getParent());
            ImageIO.write(image, "png", output.toFile());
            assertTrue(Files.size(output) > 1000);
        }
    }

    private static byte[] simplePdf() throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(300, 300));
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 200);
                content.showText("Hello Resume");
                content.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    /** 仿一份两段式中文简历：姓名、联系方式、教育经历、项目描述。 */
    private static byte[] chineseResumePdf() throws Exception {
        Path fontPath = Path.of("C:\\Windows\\Fonts\\simhei.ttf");
        try (PDDocument document = new PDDocument()) {
            PDFont font = Files.exists(fontPath)
                    ? PDType0Font.load(document, fontPath.toFile())
                    : new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                write(content, font, 20, 50, 790, "黄洋");
                write(content, font, 10, 50, 762, "性    别：男        籍    贯：广东");
                write(content, font, 10, 50, 742, "电    话：13450150516    邮    箱：1090219865@qq.com");

                write(content, font, 12, 50, 700, "教育背景");
                write(content, font, 10, 50, 678, "2023.09 - 2027.06   广州理工学院   软件工程（本科）");
                write(content, font, 10, 50, 658, "专业成绩：GPA 3.9/5（专业前5%）；主修课程：数据结构与算法、数据库系统、Java、操作系统");
                write(content, font, 10, 55, 638, "作为学校学生代表（学校前0.5%）赴澳门大学交流，获荣誉证书");

                write(content, font, 12, 50, 600, "实习与项目经历");
                write(content, font, 10, 50, 578, "2026.07 - 2026.09   佳都科技集团股份有限公司");
                write(content, font, 10, 50, 558, "2026.03 - 2026.06   广州市先峰科技服务有限公司");
                write(content, font, 10, 50, 520, "AI 智能视频标签与语音调度平台");
                write(content, font, 10, 50, 498, "目 标：面向视频监控业务场景，构建智能视频标注与语音调度平台；");
                write(content, font, 10, 50, 478, "标注任务全生命周期管理：针对平台、目录、设备等多类型标注对象关系复杂、任务状态分散的问题，");
                write(content, font, 10, 50, 458, "基于 Spring Boot 和 MyBatis-Plus 实现任务创建、启动、编辑、禁用、删除及状态流转。");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private static void write(PDPageContentStream content, PDFont font, float size,
                              float x, float y, String text) throws Exception {
        content.beginText();
        content.setFont(font, size);
        content.newLineAtOffset(x, y);
        content.showText(text);
        content.endText();
    }

    /**
     * 仿"真简历里那种底色带"：标题不是纯文字，而是坐在一条横贯整行的实心色带上，
     * 带子从页边 19.6 点开始、比按钮式的行高厚一圈。
     *
     * <p>PDF 的 y 从页底往上数，这里统一按"距页顶多少点"写，再换算成基线。</p>
     */
    private static byte[] bandedResumePdf() throws Exception {
        float pageWidth = 615f;
        float pageHeight = 870f;
        Path fontPath = Path.of("C:\\Windows\\Fonts\\simhei.ttf");
        try (PDDocument document = new PDDocument()) {
            PDFont font = Files.exists(fontPath)
                    ? PDType0Font.load(document, fontPath.toFile())
                    : new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDPage page = new PDPage(new PDRectangle(pageWidth, pageHeight));
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                write(content, font, 16, 40, pageHeight - 60, "黄洋");
                write(content, font, 10, 40, pageHeight - 90, "电    话：13450150516");

                // 底色带：距页顶 96 → 132 点，横向 19.6 → 600 点
                content.setNonStrokingColor(new java.awt.Color(0x3F, 0x5A, 0x80));
                content.addRect(19.6f, pageHeight - 132, 600 - 19.6f, 36);
                content.fill();

                content.setNonStrokingColor(java.awt.Color.BLACK);
                write(content, font, 12, 34, pageHeight - 122, "教育背景");
                write(content, font, 10, 40, pageHeight - 155, "2023.09 - 2027.06   广州理工学院   软件工程（本科）");
                write(content, font, 10, 40, pageHeight - 175, "专业成绩：GPA 3.9/5（专业前5%）");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    /** 两页：第 1 页的「实习经历」写到一半，第 2 页开头接着写，然后才是「项目经验」。 */
    private static byte[] twoPageResumePdf() throws Exception {
        Path fontPath = Path.of("C:\\Windows\\Fonts\\simhei.ttf");
        try (PDDocument document = new PDDocument()) {
            PDFont font = Files.exists(fontPath)
                    ? PDType0Font.load(document, fontPath.toFile())
                    : new PDType1Font(Standard14Fonts.FontName.HELVETICA);

            PDPage first = new PDPage(PDRectangle.A4);
            document.addPage(first);
            try (PDPageContentStream content = new PDPageContentStream(document, first)) {
                write(content, font, 20, 50, 790, "黄洋");
                write(content, font, 10, 50, 762, "电    话：13450150516");
                write(content, font, 12, 50, 700, "实习经历");
                write(content, font, 10, 50, 678, "2026.07 - 2026.09   佳都科技集团股份有限公司");
                write(content, font, 10, 50, 650, "标注任务全生命周期管理：针对平台、目录、设备等多类型标注对象关系复杂、");
                write(content, font, 10, 50, 630, "任务状态分散的问题，基于 Spring Boot 和 MyBatis-Plus 实现任务创建");
            }

            PDPage second = new PDPage(PDRectangle.A4);
            document.addPage(second);
            try (PDPageContentStream content = new PDPageContentStream(document, second)) {
                write(content, font, 10, 50, 780, "这是第二页的续写内容：支撑日均 500+ 任务稳定处理");
                write(content, font, 10, 50, 760, "以及结果异步回流。");
                write(content, font, 12, 50, 700, "项目经验");
                write(content, font, 10, 50, 678, "PanelAI：基于多 Agent 协作的智能面试与职业评估平台");
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }
}
