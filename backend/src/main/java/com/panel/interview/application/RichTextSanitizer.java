package com.panel.interview.application;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 简历板块富文本净化。
 *
 * <p>编辑器把每个板块存成一小段 HTML，再原样渲染回 PDF 画布
 * （{@code dangerouslySetInnerHTML}）。这条链路上任何可执行内容都是存储型 XSS，
 * 而令牌就放在 localStorage 里——一次注入等于长期接管账号，所以：
 * <b>入库前按白名单重建，而不是"过滤掉危险标签"</b>。</p>
 *
 * <p>三条硬约束：</p>
 * <ul>
 *   <li>只允许排版类标签与内联样式；{@code script/iframe/style/on*} 一律丢弃；</li>
 *   <li>{@code img} 只接受 {@code data:image/*} 内联图，外链图片会变成跟踪像素，直接丢；</li>
 *   <li>{@code style} 里出现 {@code url(}/{@code expression}/{@code javascript:} 时整条样式丢弃。</li>
 * </ul>
 */
public final class RichTextSanitizer {

    private static final Safelist SAFELIST = Safelist.relaxed()
            .addTags("span", "u", "s", "sub", "sup", "div", "p", "br", "font")
            .addAttributes(":all", "style")
            .addAttributes("font", "color", "size", "face")
            .addAttributes("img", "src", "alt", "width", "height")
            // 外链图片一律不落地：只保留内联图（编辑器插图就是 base64）
            .removeProtocols("img", "src", "http", "https")
            .addProtocols("img", "src", "data");

    /** CSS 里能执行脚本或发请求的写法。 */
    private static final Pattern DANGEROUS_CSS = Pattern.compile(
            "(?i)(url\\s*\\(|expression\\s*\\(|javascript\\s*:|behavior\\s*:|@import)");

    private RichTextSanitizer() {
    }

    public static String sanitize(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        Document clean = Jsoup.parseBodyFragment(html);
        clean.outputSettings().prettyPrint(false);
        // relaxed 白名单会保留 style：逐条清洗，避免 CSS 注入绕过
        for (Element element : clean.select("[style]")) {
            String style = element.attr("style");
            if (DANGEROUS_CSS.matcher(style).find() || style.toLowerCase(Locale.ROOT).contains("</")) {
                element.removeAttr("style");
            }
        }
        // 内联图只允许图片 MIME：data:text/html 之类一律摘掉 src
        for (Element image : clean.select("img[src]")) {
            String src = image.attr("src").trim();
            if (!src.toLowerCase(Locale.ROOT).startsWith("data:image/")) {
                image.removeAttr("src");
            }
        }
        String body = Jsoup.clean(clean.body().html(), SAFELIST);
        // clean 会再走一遍白名单，但上面的 style "清理后保留"需要在第二次清洗后仍生效，
        // 所以这里再对结果做一次同样的 CSS 检查
        Document result = Jsoup.parseBodyFragment(body);
        for (Element element : result.select("[style]")) {
            String style = element.attr("style");
            if (DANGEROUS_CSS.matcher(style).find()) {
                element.removeAttr("style");
            }
        }
        return result.body().html();
    }
}
