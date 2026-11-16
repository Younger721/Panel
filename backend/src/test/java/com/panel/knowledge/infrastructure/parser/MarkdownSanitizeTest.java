package com.panel.knowledge.infrastructure.parser;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 清洗 markdown 装饰时不许伤到技术名词。
 *
 * <p>原来是 <code>replaceAll("[`*_]", "")</code>，无差别删字符：{@code idx_name_age}
 * 被洗成 {@code idxnameage}，{@code __init__} 被洗成 {@code init}。
 * 这些词是面试笔记里的关键词，检索和复制都靠它。</p>
 */
class MarkdownSanitizeTest {

    private final DocumentParserRegistry registry = DocumentParserRegistry.standard();

    private String parsedText(String markdown) {
        return registry.parseStructured("a.md", "text/markdown", markdown.getBytes(StandardCharsets.UTF_8))
                .blocks().stream()
                .map(DocumentBlock::text)
                .reduce("", (a, b) -> a + "\n" + b);
    }

    @Test
    void 标识符里的下划线要留着() {
        String text = parsedText("索引 idx_name_age 的叶子记录。\n");
        assertThat(text).contains("idx_name_age");
    }

    @Test
    void 双下划线包裹的粗体仍然去掉() {
        String text = parsedText("__重点__：聚簇索引。\n");
        assertThat(text).contains("重点").doesNotContain("__");
    }

    @Test
    void 星号粗体仍然去掉() {
        String text = parsedText("**注意**：不要回表。\n");
        assertThat(text).contains("注意").doesNotContain("**");
    }

    @Test
    void 行内代码反引号去掉但内容留着() {
        String text = parsedText("用 `@Slf4j` 打日志，字段 snake_case_name 不要动。\n");
        assertThat(text).contains("@Slf4j").contains("snake_case_name").doesNotContain("`");
    }

    @Test
    void 单个星号作乘法或通配时不误删() {
        String text = parsedText("SELECT * FROM user WHERE id = 1;\n");
        assertThat(text).contains("SELECT * FROM user");
    }
}
