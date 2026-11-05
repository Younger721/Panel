package com.nova.interview.agent;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Agent 输出 JSON 的统一解析入口。
 *
 * <p>模型偶尔会包 ```json 或加一句解释，所以在最外层花括号之间裁剪；解析失败
 * 一律抛异常，让上层决定是重试还是标记失败——绝不"猜一个"结构继续走。</p>
 */
public final class AgentJson {

    private AgentJson() {
    }

    public static <T> T parse(ObjectMapper mapper, String raw, Class<T> type, String agent) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException(agent + " returned empty content");
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException(agent + " returned no JSON object: " + abbreviate(raw));
        }
        try {
            return mapper.readValue(raw.substring(start, end + 1), type);
        } catch (Exception ex) {
            throw new IllegalStateException(agent + " returned unparsable JSON: " + abbreviate(raw), ex);
        }
    }

    public static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 300 ? value : value.substring(0, 300) + "...";
    }
}
