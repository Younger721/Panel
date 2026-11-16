package com.panel.knowledge.infrastructure.parser;

import java.util.List;

/** Parsed document with source order and structural boundaries retained. */
public record ParsedDocument(List<DocumentBlock> blocks) {
    public ParsedDocument {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }

    /**
     * 拼回纯文本，用于抽题等下游环节。
     *
     * <p>两个细节决定抽题能不能断对边界：</p>
     * <ul>
     *   <li><b>代码块带围栏</b>：不带围栏的话，抽出来的答案里代码是一堆没有边界的行，前端整段塌成文字。
     *       实测 532 道题里带代码块的一个都没有，而原文里有 88 个切片含代码。</li>
     *   <li><b>块之间空一行</b>：抽题靠"题号前面是空行"区分题号与答案里的有序列表。
     *       原来用单个换行拼，题号行永远不满足条件，只能退回"像不像疑问句"，
     *       于是「5. Java 数组常用方法」这种陈述式题号被漏掉，上一题的答案一路吞下去。</li>
     * </ul>
     */
    public String plainText() {
        return blocks.stream()
                .map(block -> switch (block.type()) {
                    case CODE -> "```\n" + block.text() + "\n```";
                    case TITLE -> "#".repeat(Math.max(1, Math.min(6, block.level()))) + " " + block.text();
                    default -> block.text();
                })
                .filter(text -> !text.isBlank())
                .reduce((a, b) -> a + "\n\n" + b)
                .orElse("");
    }
}
