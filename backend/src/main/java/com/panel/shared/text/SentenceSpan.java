package com.panel.shared.text;

import java.util.function.IntPredicate;

/**
 * 把"命中区间"扩到完整的句子 / 条目。
 *
 * <p>模型的 quote 只是锚点：为了不跑偏，prompt 要求它逐字复制 10-60 字，于是经常停在
 * 半个词上（"…并结合批量"、"…Chat 抽"），高亮出来像被切断的，读的人得自己往后脑补半句。
 * 这里按句读把两端补齐：</p>
 *
 * <ul>
 *   <li>向右走到句末标点，<b>并把标点算进来</b>；</li>
 *   <li>向左走到上一个句读之后（不含那个标点）；</li>
 *   <li>遇到换行、条目符号（•）、竖线这类"版式分界"就停，不越界到上一条/下一段；</li>
 *   <li>两端各有字符上限，防止一句话写得极长时把整段都点亮。</li>
 * </ul>
 *
 * <p>{@code joinedLeft / joinedRight} 是给调用方的几何/结构约束：{@code joined(i)} 表示
 * "第 i 与 i+1 个字符之间可以连过去"。PDF 那边用它挡住跨行距、跨字号（正文 → 标题）的扩张，
 * 并且左边的限制比右边严；纯文本那边直接放行。这样"语气上的一句"和"版面上的一段"
 * 就不会互相打脸。</p>
 */
public final class SentenceSpan {

    /** 中文句读（PDF 抽文通常已被折成全角/半角 ASCII，这里两套都留着）。 */
    private static final String SENTENCE_END = "。！？；!?;";

    /** 段落 / 条目分界：遇到就停，且不纳入区间。 */
    private static final String MARKER = "\n\r\u2028\u2029•·●▪◆■|｜";

    /** 向左最多补多少字；简历里一句长句也就 40-60 字，80 足够覆盖句首。 */
    private static final int MAX_LEFT = 80;

    /** 向右最多补多少字；超过就当这句没写完，停在原地也好过点亮一大段。 */
    private static final int MAX_RIGHT = 160;

    private SentenceSpan() {
    }

    /** 不限制连续性（纯文本场景，两个方向都放行）。 */
    public static int[] bounds(String text, int start, int end) {
        return bounds(text, start, end, index -> true, index -> true);
    }

    /** 两个方向共用同一条连续性判断。 */
    public static int[] bounds(String text, int start, int end, IntPredicate joined) {
        return bounds(text, start, end, joined, joined);
    }

    /**
     * @param text        全文（原样或归一化都可以，只要和调用方给的 start/end 同一套口径）
     * @param start       命中区间的起点（含）
     * @param end         命中区间的终点（不含）
     * @param joinedLeft  向左走时：joinedLeft(i) = 第 i 与 i+1 个字符之间可以连过去
     * @param joinedRight 向右走时：同上
     * @return {from, to}，调用方按它截取
     */
    public static int[] bounds(String text, int start, int end,
                               IntPredicate joinedLeft, IntPredicate joinedRight) {
        if (text == null || text.isEmpty()) {
            int safe = Math.max(0, start);
            return new int[]{safe, Math.max(safe, end)};
        }
        int length = text.length();
        int from = Math.max(0, Math.min(start, length));
        int to = Math.max(from, Math.min(end, length));

        while (from > 0 && start - from < MAX_LEFT
                && !stopsAt(text, from - 1) && joinedLeft.test(from - 1)) {
            from--;
        }
        // 锚点本身已经收在句读上：这就是完整的一句，别再往后蔓延到下一句
        boolean endsByMark = to > 0 && endsSentence(text.substring(to - 1, to));
        if (!endsByMark) {
            while (to < length && to - end < MAX_RIGHT
                    && !stopsAt(text, to) && joinedRight.test(to - 1)) {
                to++;
            }
            // 右端刚好停在句号上：把它收进来，高亮才是"一整句"
            if (to < length && stopsAt(text, to) && !isMarker(text.charAt(to))) {
                to++;
            }
        }
        // 条目符号/换行已经被挡在外面，剩下的缩进空格一并去掉
        while (from < to && Character.isWhitespace(text.charAt(from))) {
            from++;
        }
        while (to > from && Character.isWhitespace(text.charAt(to - 1))) {
            to--;
        }
        return new int[]{from, to};
    }

    /**
     * {@code text[index]} 是不是"到此为止"的分界。
     *
     * <p>半角句点一律不算句读：简历里 "3.9/5"、"2023.09"、"1090219865@qq.com" 太多，
     * 按字符硬切会把数字和域名全切碎。PDF 抽文里的中文句号保留 '\u3002' 原样
     * （见 {@code PdfPreview.fold}），所以正常句末照样认得出来。</p>
     */
    public static boolean stopsAt(String text, int index) {
        if (text == null || index < 0 || index >= text.length()) {
            return false;
        }
        char value = text.charAt(index);
        return isMarker(value) || SENTENCE_END.indexOf(value) >= 0;
    }

    /** 这段文字是否已经以句读收尾（用来判断还要不要再补）。 */
    public static boolean endsSentence(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return stopsAt(text, text.length() - 1) && !isMarker(text.charAt(text.length() - 1));
    }

    private static boolean isMarker(char value) {
        return MARKER.indexOf(value) >= 0;
    }
}
