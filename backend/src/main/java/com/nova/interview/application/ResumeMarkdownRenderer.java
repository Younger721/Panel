package com.nova.interview.application;

import com.nova.knowledge.infrastructure.parser.DocumentBlock;
import com.nova.knowledge.infrastructure.parser.DocumentBlockType;
import com.nova.knowledge.infrastructure.parser.ParsedDocument;

import java.util.ArrayList;
import java.util.List;

/**
 * 把解析出来的结构化文档渲染成 Markdown，并给每个块一个稳定 id。
 *
 * <p>为什么要有稳定的块 id：前端的批注是"挂在某一段的某个片段上"的，
 * 只存原文引用（quote）会在同文重复出现时定位到错误的段落；存
 * {@code blockId + quote + 起止偏移} 才能精确高亮，且与渲染顺序解耦。</p>
 */
public final class ResumeMarkdownRenderer {

    private ResumeMarkdownRenderer() {
    }

    public record Block(String id, String type, int level, String text) {
    }

    public record Rendered(String markdown, List<Block> blocks) {
    }

    public static Rendered render(ParsedDocument document) {
        List<Block> blocks = new ArrayList<>();
        StringBuilder markdown = new StringBuilder();
        int index = 0;
        for (DocumentBlock block : document.blocks()) {
            String id = "b" + (++index);
            blocks.add(new Block(id, block.type().name(), block.level(), block.text()));
            markdown.append(toMarkdown(block)).append("\n\n");
        }
        return new Rendered(markdown.toString().trim(), blocks);
    }

    private static String toMarkdown(DocumentBlock block) {
        return switch (block.type()) {
            case TITLE -> "#".repeat(Math.min(6, Math.max(1, block.level() + 1))) + " " + block.text();
            case LIST_ITEM -> "  ".repeat(Math.max(0, block.level() - 1)) + "- " + block.text();
            case TABLE_ROW -> tableRow(block);
            case CLAUSE, PARAGRAPH -> block.text();
        };
    }

    /** 表格块只保住了表头与一行内容，退化成文本比伪造成表格更诚实。 */
    private static String tableRow(DocumentBlock block) {
        if (!block.hasTableHeaders()) {
            return block.text();
        }
        return "| " + String.join(" | ", block.tableHeaders()) + " |";
    }

    /** 仅用于测试与调试：把块拼回纯文本。 */
    public static String plainText(List<Block> blocks) {
        return blocks.stream().map(Block::text).reduce((a, b) -> a + "\n" + b).orElse("");
    }

    /** 找到某段文字所属的块 id；找不到返回 null，由调用方决定降级策略。 */
    public static String blockOf(List<Block> blocks, String quote) {
        if (quote == null || quote.isBlank()) {
            return null;
        }
        String needle = normalize(quote);
        for (Block block : blocks) {
            if (normalize(block.text()).contains(needle)) {
                return block.id();
            }
        }
        return null;
    }

    /** 全半角、空白归一：模型引用的片段常与原文差一个空格或标点。 */
    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('　', ' ')
                .replaceAll("\\s+", "")
                .replace('：', ':')
                .replace('，', ',')
                .replace('（', '(')
                .replace('）', ')');
    }
}
