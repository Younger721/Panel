package com.panel.interview.application;

import com.panel.knowledge.infrastructure.parser.DocumentParserRegistry;
import com.panel.knowledge.infrastructure.parser.ParsedDocument;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Markdown 内容稿的往返：渲染成 md → 再解析 → 再渲染，两次输出必须一模一样。
 *
 * <p>原来 {@link ResumeMarkdownRenderer} 把 {@code level} 写成 {@code level + 1}，
 * 而 markdown 解析端拿 {@code #} 的个数当 level。于是 md 编辑器每保存一轮，
 * 姓名和章节就往下沉一级（{@code # 张三} → {@code ## 张三} → {@code ### 张三}），
 * 到 6 级才停——用户看到的是"我就改了一行项目描述，标题怎么跑偏了"。</p>
 */
class ResumeMarkdownRoundTripTest {

    private final DocumentParserRegistry registry = DocumentParserRegistry.standard();

    /** 走一遍"内容稿 → 结构化块 → 渲染回 md"。 */
    private String render(String markdown) {
        ParsedDocument document = registry.parseStructured("张三-简历.md", "text/markdown",
                markdown.getBytes(StandardCharsets.UTF_8));
        return ResumeMarkdownRenderer.render(document).markdown();
    }

    @Test
    void 标题层级渲染回md后不再下沉() {
        String markdown = """
                # 张三

                Java 后端开发工程师 · 4 年经验

                ## 项目经历

                ### 智能面试平台 Panel | 2024.03 – 至今

                - 主导检索链路重构，P95 从 2s 降到 200ms
                - 负责批注锚点定位，覆盖率 96%
                """;

        String once = render(markdown);
        String twice = render(once);

        assertThat(once).startsWith("# 张三");
        assertThat(once).contains("## 项目经历");
        assertThat(once).contains("### 智能面试平台 Panel | 2024.03 – 至今");
        // 幂等：这是"保存多次结构不散"的判据
        assertThat(twice).isEqualTo(once);
    }

    @Test
    void 自由书写的md解析出标题与列表() {
        String markdown = """
                # 张三

                ## 项目经历

                ### A 项目 | 2023.01 – 2024.02
                - 主导检索链路重构，P95 从 2s 降到 200ms
                * 负责批注锚点定位，覆盖率 96%
                1. 带班两名后端
                """;

        ParsedDocument document = registry.parseStructured("x.md", "text/markdown",
                markdown.getBytes(StandardCharsets.UTF_8));

        assertThat(document.blocks())
                .extracting(block -> block.type().name())
                .contains("TITLE", "LIST_ITEM");
        assertThat(document.blocks())
                .extracting(block -> block.text())
                .contains("张三", "项目经历", "主导检索链路重构，P95 从 2s 降到 200ms");
    }

    @Test
    void 用户随手写的代码块不会炸掉解析() {
        String markdown = """
                # 张三

                ## 技能

                ```java
                public class A { }
                ```

                ## 项目经历

                - 收尾
                """;

        assertThat(render(markdown)).contains("# 张三").contains("## 项目经历");
    }
}
