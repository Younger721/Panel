package com.panel.interview.agent;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Agent 输出 JSON 的统一解析入口。
 *
 * <p>模型偶尔会包 ```json 或加一句解释，所以在最外层花括号之间裁剪；解析失败
 * 一律抛异常，让上层决定是重试还是标记失败——绝不"猜一个"结构继续走。</p>
 *
 * <p>唯一的例外是<b>被 max tokens 截断</b>的输出：模型说到一半被切断，
 * 结尾是半个对象。这种 JSON 现在会先尝试"截到最后一个完整结构并补齐括号"，
 * 补得出来就用已有部分继续。理由很实际——一份简历的批注分三个模块各调一次模型，
 * 只要有一个模块因为输出太长被截断，整份简历就会 FAILED，用户看到的是
 * "解析失败"，而实际上前两个模块的批注已经完全可用了。</p>
 */
public final class AgentJson {

    private AgentJson() {
    }

    public static <T> T parse(ObjectMapper mapper, String raw, Class<T> type, String agent) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException(agent + " returned empty content");
        }
        String source = escapeControlChars(raw);
        int start = source.indexOf('{');
        int end = source.lastIndexOf('}');
        if (start < 0) {
            throw new IllegalStateException(agent + " returned no JSON object: " + abbreviate(raw));
        }
        if (end > start) {
            try {
                return mapper.readValue(source.substring(start, end + 1), type);
            } catch (Exception ignored) {
                // 落到下面的截断修复
            }
        }
        String repaired = repairTruncated(source.substring(start));
        if (repaired != null) {
            try {
                return mapper.readValue(repaired, type);
            } catch (Exception ex) {
                throw new IllegalStateException(agent + " returned unparsable JSON: " + abbreviate(raw), ex);
            }
        }
        throw new IllegalStateException(agent + " returned unparsable JSON: " + abbreviate(raw));
    }

    /**
     * 把字符串字面量里的裸控制字符转义掉。
     *
     * <p>模型写长文本时经常直接把换行打进 JSON 字符串里（"第一点……⏎第二点……"），
     * 而 Jackson 默认拒绝未转义的控制字符，整个响应就被判成"unparsable JSON"。
     * 症状极具迷惑性：日志里打印出来的片段明明是一个工整的 JSON 对象，
     * 只有尾部的裸换行藏在 300 字摘要之外。</p>
     *
     * <p>实测：一份 PDF 简历里两个内容最长的模块（实习经历、项目经历）连续三次
     * 都栽在这里，其余短模块全部正常——短模块只是恰好没在字符串里换行而已。</p>
     *
     * <p>只动字符串内部，引号和转义序列原样保留，所以对合法 JSON 是恒等变换。</p>
     */
    static String escapeControlChars(String raw) {
        StringBuilder out = new StringBuilder(raw.length() + 16);
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (!inString) {
                if (c == '"') {
                    inString = true;
                }
                out.append(c);
                continue;
            }
            if (escaped) {
                escaped = false;
                out.append(c);
                continue;
            }
            if (c == '\\') {
                escaped = true;
                out.append(c);
                continue;
            }
            if (c == '"') {
                inString = false;
                out.append(c);
                continue;
            }
            switch (c) {
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /**
     * 把"说到一半"的 JSON 截到最后一个完整结构，并补齐未闭合的括号。
     *
     * @return 补得出来的合法 JSON；实在找不到一个完整结构时返回 null
     */
    static String repairTruncated(String raw) {
        Deque<Character> stack = new ArrayDeque<>();
        boolean inString = false;
        boolean escaped = false;
        int lastComplete = -1;

        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '{', '[' -> stack.push(c);
                case '}', ']' -> {
                    if (!stack.isEmpty()) {
                        stack.pop();
                    }
                    // 一个完整结构结束：这里可以安全截断
                    lastComplete = i;
                }
                default -> {
                }
            }
        }
        if (lastComplete < 0) {
            return null;
        }
        String head = raw.substring(0, lastComplete + 1);
        // 重新数一遍 head 里没闭合的括号，按相反顺序补上
        Deque<Character> open = new ArrayDeque<>();
        inString = false;
        escaped = false;
        for (int i = 0; i < head.length(); i++) {
            char c = head.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '{', '[' -> open.push(c);
                case '}', ']' -> {
                    if (!open.isEmpty()) {
                        open.pop();
                    }
                }
                default -> {
                }
            }
        }
        StringBuilder repaired = new StringBuilder(head);
        while (!open.isEmpty()) {
            repaired.append(open.pop() == '{' ? '}' : ']');
        }
        return repaired.toString();
    }

    public static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 300 ? value : value.substring(0, 300) + "...";
    }
}
