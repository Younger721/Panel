package com.panel.knowledge.infrastructure.parser;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Markdown 解析的真实事故。
 *
 * <p>一篇带代码块的面试文档（{@code 2026-08-20-用友-电话初筛未通过.md}）上传后直接失败，
 * 报 {@code document block text is required}——因为闭合围栏那行 <code>```</code> 被当成正文，
 * 洗完 markdown 装饰后成了空串，而 {@link DocumentBlock} 不接受空文本。</p>
 *
 * <p>修完崩溃还有第二个问题：代码块被 strip 掉缩进、还会被切成两片。
 * 面试笔记里 Python 的缩进就是语法，切断了等于给了半段不可运行的代码。</p>
 */
class MarkdownParseTest {

    private final DocumentParserRegistry registry = DocumentParserRegistry.standard();
    private final KbChunker chunker = new KbChunker();

    private ParsedDocument parse(String markdown) {
        return registry.parseStructured("a.md", "text/markdown",
                markdown.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void 带代码块围栏的文档不该炸() {
        String markdown = """
                # 标题

                ## 1. 自定义注解配合 AOP

                ### 示例

                ```java
                @Around("@annotation(operationLog)")
                public Object record(ProceedingJoinPoint point) {
                    return point.proceed();
                }
                ```

                正文继续。
                """;
        assertThatCode(() -> parse(markdown)).doesNotThrowAnyException();
        assertThat(parse(markdown).blocks()).isNotEmpty();
    }

    @Test
    void 代码块缩进原样保留() {
        String markdown = """
                ```python
                def route(task):
                    if task.needs_search:
                        return search(task.query)
                    return finish()
                ```
                """;
        List<DocumentBlock> codeBlocks = parse(markdown).blocks().stream()
                .filter(block -> block.type() == DocumentBlockType.CODE)
                .toList();
        assertThat(codeBlocks).hasSize(1);
        assertThat(codeBlocks.get(0).text())
                .contains("\n    if task.needs_search:\n        return search(task.query)");
    }

    @Test
    void 一个围栏里的代码只产生一个块不被切成多段() {
        String markdown = """
                ```java
                class A {
                    void m() {
                    }
                }
                ```

                ```java
                class B {
                }
                ```
                """;
        List<DocumentBlock> codeBlocks = parse(markdown).blocks().stream()
                .filter(block -> block.type() == DocumentBlockType.CODE)
                .toList();
        assertThat(codeBlocks).hasSize(2);
        assertThat(codeBlocks.get(0).text()).contains("class A").contains("void m()");
        assertThat(codeBlocks.get(1).text()).contains("class B");
    }

    @Test
    void 代码块在切片里完整不被劈开() {
        // 一段超过单片上限的 Python 代码：切片只能在换行处断开，不能把函数截成两半
        StringBuilder markdown = new StringBuilder("## 示例\n\n```python\n");
        for (int i = 0; i < 60; i++) {
            markdown.append("def func").append(i).append("(x):\n    return x + ").append(i).append("\n");
        }
        markdown.append("```\n");
        List<String> chunks = chunker.chunk(parse(markdown.toString()),
                        com.panel.knowledge.domain.model.KbChunkStrategy.TECH_DOC, 300, 0)
                .stream().map(KbChunker.Draft::text).toList();
        assertThat(chunks).isNotEmpty();
        for (String chunk : chunks) {
            if (!chunk.contains("def func")) {
                continue;
            }
            assertThat(chunk.lines().filter(line -> line.startsWith("def func")).count())
                    .as("每个 def 都是完整一行，说明没被横切")
                    .isPositive();
            assertThat(chunk).as("切片开头不能是缩进继续行").doesNotContainPattern("^\\s{2,}\\S");
        }
    }

    @Test
    void 代码块内容原样保留不洗掉星号下划线() {
        String markdown = """
                ```java
                int[] a_b = {1, 2};   // snake_case 要留着
                System.out.println("*ok*");
                ```
                """;
        String joined = parse(markdown).blocks().stream()
                .map(DocumentBlock::text)
                .reduce("", (a, b) -> a + "\n" + b);
        assertThat(joined).contains("a_b").contains("*ok*");
        assertThat(joined).doesNotContain("```");
    }

    @Test
    void 分隔线不进切片() {
        String markdown = """
                上面一段。

                ---

                下面一段。
                """;
        assertThat(parse(markdown).blocks())
                .extracting(DocumentBlock::text)
                .doesNotContain("---")
                .contains("上面一段。", "下面一段。");
    }

    @Test
    void 真实文件能解析出多个块() throws Exception {
        Path sample = Path.of("src", "test", "resources", "kb", "interview-with-code.md");
        if (!Files.exists(sample)) {
            return;
        }
        ParsedDocument parsed = parse(Files.readString(sample, StandardCharsets.UTF_8));
        assertThat(parsed.blocks()).hasSizeGreaterThan(10);
        assertThat(parsed.blocks()).allSatisfy(block -> assertThat(block.text()).isNotBlank());
    }
}
