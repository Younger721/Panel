package com.panel.interview.infrastructure.ocr;

import com.panel.interview.infrastructure.pdf.PdfPreview;
import com.panel.ocr.application.OcrProperties;
import com.panel.ocr.domain.OcrGateway;
import com.panel.ocr.domain.OcrOptions;
import com.panel.ocr.domain.OcrRequest;
import com.panel.ocr.domain.OcrResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 没有文本层的简历兜底识别。
 *
 * <p>为什么需要它：正常链路是 PDFBox 抽文本层（确定性、零成本），但求职者手里
 * 大量是「手机拍的简历转成的 PDF」——这类文件一个字都抽不出来，以前直接以
 * {@code resume text is empty after parsing} 失败。而项目里本来就有 OCR 能力，
 * 只是没接到简历链路上。</p>
 *
 * <p>为什么不走 {@code OcrService}：那是「上传 → 拿 uploadId → 按 ID 识别」的
 * 用例层，带对象存储登记和租户配额；简历文件已经在自己的对象存储里，
 * 这里只需要底层 {@link OcrGateway} 把位图认成文字。</p>
 *
 * <p>三个刻意的取舍：</p>
 * <ul>
 *   <li><b>限页数</b>：每页一次模型调用，简历超过 6 页的部分不认——多认一页的
 *       成本（几秒 + 一次计费）换不来有效信息。</li>
 *   <li><b>2 倍渲染</b>：约 144 DPI，正文小五号字足够认；再高只是让 PNG 变大。</li>
 *   <li><b>单页失败不拖垮整份</b>：简历第 3 页是张图，不该让第 1、2 页的文字一起丢。</li>
 * </ul>
 */
@Service
public class ScannedResumeOcr {

    private static final Logger log = LoggerFactory.getLogger(ScannedResumeOcr.class);

    private static final int MAX_PAGES = 6;
    private static final float RENDER_SCALE = 2f;

    private final OcrGateway ocr;
    private final boolean enabled;

    public ScannedResumeOcr(OcrGateway ocr, OcrProperties properties) {
        this.ocr = ocr;
        this.enabled = properties.isEnabled();
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * 逐页识别 PDF。
     *
     * @return 拼好的正文；一页都没认出来时返回空串（由调用方决定怎么报错）
     */
    public String extract(byte[] pdfBytes, String fileName) {
        if (!enabled) {
            log.debug("Scanned resume OCR skipped: panel.ocr.enabled=false, file={}", fileName);
            return "";
        }
        StringBuilder text = new StringBuilder();
        try (PdfPreview preview = PdfPreview.open(pdfBytes)) {
            int total = preview.pageCount();
            int pages = Math.min(total, MAX_PAGES);
            if (total > MAX_PAGES) {
                log.warn("Scanned resume has {} pages, only first {} are OCR'd: file={}",
                        total, MAX_PAGES, fileName);
            }
            for (int page = 1; page <= pages; page++) {
                String pageText = recognizePage(preview, page, fileName);
                if (!pageText.isBlank()) {
                    text.append(pageText.strip()).append("\n\n");
                }
            }
        } catch (Exception ex) {
            // 打不开 PDF（加密、损坏）也归到这里：调用方按"没认出来"处理
            log.warn("Scanned resume OCR failed: file={}, error={}", fileName, ex.toString());
            return "";
        }
        return text.toString().strip();
    }

    private String recognizePage(PdfPreview preview, int page, String fileName) {
        try {
            byte[] png = preview.renderPage(page, RENDER_SCALE);
            OcrResult result = ocr.recognize(new OcrRequest(
                    fileName + "#page" + page + ".png", "image/png", png, OcrOptions.defaults()));
            String markdown = result.markdown();
            return markdown == null ? "" : markdown;
        } catch (RuntimeException ex) {
            log.warn("Scanned resume page OCR failed: file={}, page={}, error={}",
                    fileName, page, ex.toString());
            return "";
        }
    }
}
