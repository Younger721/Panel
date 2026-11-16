package com.panel.interview.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 板块富文本净化。
 *
 * <p>存进 {@code edit_layer} 的 HTML 会在 PDF 画布上用 {@code dangerouslySetInnerHTML}
 * 渲染回来，而令牌就在 localStorage 里——这里漏一个 {@code onerror} 就等于
 * 一次存储型 XSS 变成长期接管。所以断言按"可执行内容必须消失"写。</p>
 */
class RichTextSanitizerTest {

    @Test
    void keepsPlainFormatting() {
        String clean = RichTextSanitizer.sanitize("<b>负责</b><span style=\"color:#333\">订单中心</span>");

        assertThat(clean).contains("<b>负责</b>");
        assertThat(clean).contains("color");
        assertThat(clean).contains("订单中心");
    }

    @Test
    void dropsScriptBlock() {
        String clean = RichTextSanitizer.sanitize("<p>前</p><script>alert(1)</script><p>后</p>");

        assertThat(clean).doesNotContainIgnoringCase("script");
        assertThat(clean).contains("前").contains("后");
    }

    @Test
    void dropsInlineEventHandlers() {
        String clean = RichTextSanitizer.sanitize("<img src=\"data:image/png;base64,AAA\" onerror=\"alert(1)\">");

        assertThat(clean).doesNotContainIgnoringCase("onerror");
        assertThat(clean).contains("data:image/png");
    }

    @Test
    void dropsJavascriptUrls() {
        String clean = RichTextSanitizer.sanitize("<a href=\"javascript:alert(1)\">点我</a>");

        assertThat(clean).doesNotContainIgnoringCase("javascript:");
        assertThat(clean).contains("点我");
    }

    @Test
    void dropsCssUrlAndExpression() {
        String clean = RichTextSanitizer.sanitize(
                "<span style=\"background:url(javascript:alert(1))\">x</span>"
                        + "<span style=\"width:expression(alert(1))\">y</span>");

        assertThat(clean).doesNotContainIgnoringCase("url(");
        assertThat(clean).doesNotContainIgnoringCase("expression");
    }

    @Test
    void dropsRemoteImages() {
        String clean = RichTextSanitizer.sanitize("<img src=\"https://evil.example/track.png\">");

        assertThat(clean).doesNotContain("evil.example");
    }

    @Test
    void handlesEmptyInput() {
        assertThat(RichTextSanitizer.sanitize(null)).isEmpty();
        assertThat(RichTextSanitizer.sanitize("   ")).isEmpty();
    }
}
