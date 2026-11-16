package com.panel.knowledge.infrastructure.parser;

import com.panel.knowledge.domain.model.KbChunkStrategy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库切片器：按标题层级聚合 + token 预算窗口 + 中文按字符回退的 overlap。
 *
 * <p>K1 先用一套通用算法覆盖 QA/TECH_DOC/JD/FAQ/GENERAL；题库的结构化导入在 K2。</p>
 */
@Component
public class KbChunker {

    public static final int MAX_CHUNKS = 2000;

    private final TokenCounter counter = new HeuristicTokenCounter();

    public record Draft(int ordinal, String heading, Integer pageNo, int tokenCount, String text) {
    }

    public List<Draft> chunk(ParsedDocument document, KbChunkStrategy strategy, int chunkSize, int overlap) {
        if (document == null || document.blocks().isEmpty()) {
            throw new IllegalArgumentException("文档没有可切片的文本");
        }
        int maxTokens = Math.max(100, chunkSize);
        int overlapChars = Math.max(0, Math.round(overlap * 1.6f));
        List<Draft> chunks = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        int bufferTokens = 0;
        String heading = null;
        String lastFlushed = null;

        for (DocumentBlock block : document.blocks()) {
            if (block.type() == DocumentBlockType.TITLE && block.level() <= 3) {
                if (!buffer.toString().isBlank()) {
                    lastFlushed = flush(chunks, buffer, heading);
                    buffer.setLength(0);
                    bufferTokens = 0;
                }
                heading = block.text();
                buffer.append(heading).append('\n');
                bufferTokens = counter.count(heading) + 1;
                continue;
            }
            // 代码块整块进同一个切片：按句子/字符硬切会把 Python 的缩进与 f-string 拆散。
            // 落地时补回围栏，前端才能按代码块渲染（等宽 + 高亮），复制出去也仍是可运行的片段。
            if (block.type() == DocumentBlockType.CODE) {
                String fenced = "```\n" + block.text() + "\n```";
                int codeTokens = counter.count(fenced);
                if (bufferTokens > 0 && bufferTokens + codeTokens > maxTokens) {
                    lastFlushed = flush(chunks, buffer, heading);
                    buffer.setLength(0);
                    if (heading != null) {
                        buffer.append(heading).append('\n');
                    }
                    String tail = overlapTail(lastFlushed, overlapChars);
                    if (!tail.isBlank()) {
                        buffer.append(tail).append('\n');
                    }
                    bufferTokens = counter.count(buffer.toString());
                }
                buffer.append(fenced).append('\n');
                bufferTokens += codeTokens;
                if (chunks.size() > MAX_CHUNKS) {
                    throw new IllegalArgumentException("切片超过 " + MAX_CHUNKS + " 片，请先拆分文档");
                }
                continue;
            }
            for (String piece : splitOversized(block.text(), maxTokens)) {
                int pieceTokens = counter.count(piece);
                if (bufferTokens > 0 && bufferTokens + pieceTokens > maxTokens) {
                    lastFlushed = flush(chunks, buffer, heading);
                    buffer.setLength(0);
                    if (heading != null) {
                        buffer.append(heading).append('\n');
                    }
                    String tail = overlapTail(lastFlushed, overlapChars);
                    if (!tail.isBlank()) {
                        buffer.append(tail).append('\n');
                    }
                    bufferTokens = counter.count(buffer.toString());
                }
                buffer.append(piece).append('\n');
                bufferTokens += pieceTokens;
                if (chunks.size() > MAX_CHUNKS) {
                    throw new IllegalArgumentException("切片超过 " + MAX_CHUNKS + " 片，请先拆分文档");
                }
            }
        }
        if (!buffer.toString().isBlank()) {
            flush(chunks, buffer, heading);
        }
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("文档没有可切片的文本");
        }
        return chunks;
    }

    private String flush(List<Draft> chunks, StringBuilder buffer, String heading) {
        String text = buffer.toString().trim();
        if (text.isBlank()) {
            return "";
        }
        chunks.add(new Draft(chunks.size(), heading, null, counter.count(text), text));
        return text;
    }

    /** 超长块先按句子切，再把过长的句子按字符硬切。 */
    private List<String> splitOversized(String text, int maxTokens) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        if (counter.count(text) <= maxTokens) {
            return List.of(text);
        }
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String sentence : text.split("(?<=[。！？!?；;\\n])")) {
            if (sentence.isBlank()) {
                continue;
            }
            if (counter.count(current.toString()) + counter.count(sentence) > maxTokens && !current.isEmpty()) {
                pieces.add(current.toString().trim());
                current.setLength(0);
            }
            if (counter.count(sentence) > maxTokens) {
                for (String hard : hardSplit(sentence, maxTokens)) {
                    pieces.add(hard);
                }
            } else {
                current.append(sentence);
            }
        }
        if (!current.isEmpty()) {
            pieces.add(current.toString().trim());
        }
        return pieces.stream().filter(piece -> !piece.isBlank()).toList();
    }

    private List<String> hardSplit(String text, int maxTokens) {
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + Math.max(50, maxTokens));
            pieces.add(text.substring(start, end).trim());
            start = end;
        }
        return pieces;
    }

    /** 取上一片结尾的一段作为 overlap；按句号/换行回退，避免从半句开始。 */
    private String overlapTail(String text, int chars) {
        if (text == null || text.isBlank() || chars <= 0) {
            return "";
        }
        int start = Math.max(0, text.length() - chars);
        String tail = text.substring(start);
        int cut = -1;
        for (int i = 0; i < tail.length() - 1; i++) {
            char ch = tail.charAt(i);
            if (ch == '。' || ch == '！' || ch == '？' || ch == '；' || ch == '\n' || ch == '.' || ch == '!' || ch == '?') {
                cut = i + 1;
            }
        }
        if (cut > 0) {
            return tail.substring(cut).trim();
        }
        return tail.trim();
    }
}
