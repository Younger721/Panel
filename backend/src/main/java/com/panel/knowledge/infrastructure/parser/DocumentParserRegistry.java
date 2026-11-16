package com.panel.knowledge.infrastructure.parser;

import com.panel.knowledge.infrastructure.parser.normalize.DocxStructureParser;
import com.panel.knowledge.infrastructure.parser.normalize.DocumentMarkdownNormalizer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.io.ByteArrayInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

public final class DocumentParserRegistry {
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern SCRIPT = Pattern.compile("(?is)<script[^>]*>.*?</script>");
    /** 列表行：无序符号或有序编号，后面必须跟空白，避免把「-1 度」当成列表 */
    private static final Pattern LIST_MARKER = Pattern.compile("^([-*+]|\\d+[.)])\\s+(.+)$");

    private final DocumentMarkdownNormalizer markdownNormalizer;

    private DocumentParserRegistry(DocumentMarkdownNormalizer markdownNormalizer) {
        this.markdownNormalizer = markdownNormalizer == null
                ? DocumentMarkdownNormalizer.create(true) : markdownNormalizer;
    }

    public static DocumentParserRegistry standard() {
        return new DocumentParserRegistry(DocumentMarkdownNormalizer.create(true));
    }

    public static DocumentParserRegistry withNormalizer(DocumentMarkdownNormalizer markdownNormalizer) {
        return new DocumentParserRegistry(markdownNormalizer);
    }

    public String parse(String fileName, String contentType, byte[] bytes) {
        return parseStructured(fileName, contentType, bytes).plainText();
    }

    public ParsedDocument parseStructured(String fileName, String contentType, byte[] bytes) {
        return RegulationStructureUpgrade.enhance(parseByFormat(fileName, contentType, bytes));
    }

    private ParsedDocument parseByFormat(String fileName, String contentType, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("document is empty");
        }
        if (markdownNormalizer.shouldConvert(fileName, contentType)) {
            return markdownStructured(markdownNormalizer.toMarkdownBytes(fileName, contentType, bytes));
        }
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (type.contains("text/plain") || name.endsWith(".txt")) {
            return lines(text(bytes));
        }
        if (type.contains("markdown") || name.endsWith(".md")) {
            return markdownStructured(bytes);
        }
        if (type.contains("text/html") || name.endsWith(".html") || name.endsWith(".htm")) {
            return lines(html(bytes));
        }
        if (type.contains("pdf") || name.endsWith(".pdf")) {
            return lines(pdf(bytes));
        }
        if (type.contains("wordprocessingml") || name.endsWith(".docx")) {
            return DocxStructureParser.parse(bytes);
        }
        throw new IllegalArgumentException("unsupported document type");
    }

    private static String text(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8).trim(); }

    private static ParsedDocument markdownStructured(byte[] bytes) {
        List<DocumentBlock> blocks = new ArrayList<>();
        List<String> codeLines = new ArrayList<>();
        boolean inCodeFence = false;
        for (String raw : new String(bytes, StandardCharsets.UTF_8).split("\\R")) {
            String line = raw.strip();
            // 代码围栏本身不是内容；进去以后行要原样留着，别拿 cleanMarkdown 把 ` * _ 洗掉
            if (line.startsWith("```") || line.startsWith("~~~")) {
                if (inCodeFence) {
                    flushCode(blocks, codeLines);
                }
                inCodeFence = !inCodeFence;
                continue;
            }
            if (inCodeFence) {
                // 缩进是代码的语法：这里只去行尾空白，保留左缩进
                codeLines.add(raw.stripTrailing());
                continue;
            }
            if (line.isBlank()) {
                continue;
            }
            // 分隔线（--- / === / ***）不进切片，否则每篇文档末尾都要多一条 "---"
            if (line.matches("^[-*_=\\s]{3,}$")) {
                continue;
            }
            var heading = Pattern.compile("^(#{1,6})\\s+(.+)$").matcher(line);
            var bullet = LIST_MARKER.matcher(line);
            if (heading.matches()) {
                addIfPresent(blocks, DocumentBlock.title(cleanMarkdown(heading.group(2)), heading.group(1).length()));
            } else if (bullet.matches()) {
                // 题号是有用信息：题库文档靠 "1." "2." 拆题，剥掉就只剩"这行像不像问题"可猜了。
                // 所以有序列表把整行（含编号）留进文本；无序符号「- * +」是纯装饰，必须剥掉——
                // ResumeMarkdownRenderer 渲染列表时会再补一次 "- "，不剥的话
                // 「渲染成 md → 再解析 → 再渲染」每走一轮前缀就多一个（- → - - → - - -）。
                String marker = bullet.group(1);
                boolean ordered = Character.isDigit(marker.charAt(0));
                addIfPresent(blocks, DocumentBlock.listItem(
                        cleanMarkdown(ordered ? line : bullet.group(2)), 0));
            } else {
                addIfPresent(blocks, DocumentBlock.paragraph(cleanMarkdown(line)));
            }
        }
        // 围栏没闭合（文件被截断）也要把攒下的代码留住
        flushCode(blocks, codeLines);
        return new ParsedDocument(blocks);
    }

    /**
     * 一段围栏里的代码作为一个块落地：整块不可切，切片器不会从中间劈开它。
     * 之前是逐行当普通段落，Python 的缩进被 strip 掉、一个 f-string 还会被切成两半。
     */
    private static void flushCode(List<DocumentBlock> blocks, List<String> codeLines) {
        while (!codeLines.isEmpty() && codeLines.get(0).isBlank()) {
            codeLines.remove(0);
        }
        while (!codeLines.isEmpty() && codeLines.get(codeLines.size() - 1).isBlank()) {
            codeLines.remove(codeLines.size() - 1);
        }
        if (codeLines.isEmpty()) {
            return;
        }
        addIfPresent(blocks, DocumentBlock.code(String.join("\n", codeLines)));
        codeLines.clear();
    }

    /**
     * 洗完 markdown 装饰后成空串的行（例如只有反引号、只有星号的装饰行）不产生块。
     * {@link DocumentBlock} 不接受空文本，之前一行孤零零的 <code>```</code> 能把整篇文档解析炸掉。
     */
    private static void addIfPresent(List<DocumentBlock> blocks, DocumentBlock block) {
        if (!block.text().isBlank()) {
            blocks.add(block);
        }
    }

    /**
     * 去掉 markdown 装饰，但只去真的装饰。
     *
     * <p>原来是无差别删掉所有 <code>* _ `</code>，把技术名词一起毁了：
     * {@code idx_name_age} 变成 {@code idxnameage}，{@code __init__} 变成 {@code init}。
     * 这些词在面试笔记里是关键词，检索命中和复制粘贴都靠它。</p>
     */
    private static String cleanMarkdown(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replaceAll("\\*\\*(.+?)\\*\\*", "$1")
                .replaceAll("__(.+?)__", "$1")
                .replaceAll("(?<![\\w*])\\*(\\S(?:.*?\\S)?)\\*(?![\\w*])", "$1")
                .replaceAll("`([^`]*)`", "$1")
                .trim();
    }

    private static String html(byte[] bytes) {
        String value = new String(bytes, StandardCharsets.UTF_8);
        value = SCRIPT.matcher(value).replaceAll(" ");
        return TAG.matcher(value).replaceAll(" ").replaceAll("\\s+", " ").trim();
    }

    private static String pdf(byte[] bytes) {
        try (var doc = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(doc).trim();
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid PDF", e);
        }
    }

    private static ParsedDocument lines(String value) {
        List<DocumentBlock> blocks = new ArrayList<>();
        for (String line : value.split("\\R")) {
            if (!line.isBlank()) {
                blocks.add(DocumentBlock.paragraph(line));
            }
        }
        if (blocks.isEmpty() && !value.isBlank()) {
            blocks.add(DocumentBlock.paragraph(value));
        }
        return new ParsedDocument(blocks);
    }
}
