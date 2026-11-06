package com.nova.interview.infrastructure.pdf;

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

    @Test
    void rendersChineseSampleForVisualInspection() throws Exception {
        Path font = Path.of("C:\\Windows\\Fonts\\simhei.ttf");
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
}
