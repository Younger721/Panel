package com.nova.ocr.infrastructure;

import com.nova.ocr.domain.OcrGateway;
import com.nova.ocr.domain.OcrRequest;
import com.nova.ocr.domain.OcrResult;
import com.nova.shared.error.BusinessException;
import com.nova.shared.error.ErrorCode;

/** 降级适配器：功能关闭或未配置 Key 时装配，任何识别请求都返回依赖不可用 */
public class UnavailableOcrAdapter implements OcrGateway {

    private final String reason;

    public UnavailableOcrAdapter(String reason) {
        this.reason = reason == null || reason.isBlank() ? "OCR 服务不可用" : reason;
    }

    @Override
    public boolean supports(String contentType) {
        // 声明支持所有类型，让失败发生在统一的 recognize 降级文案上
        return true;
    }

    @Override
    public OcrResult recognize(OcrRequest request) {
        throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, reason);
    }
}
