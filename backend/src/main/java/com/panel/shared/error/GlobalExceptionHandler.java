package com.panel.shared.error;

import com.panel.shared.api.ApiResponse;
import com.panel.shared.api.TraceIdFilter;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final int MAX_CAUSE_DEPTH = 8;
    private static final int MAX_FRAMES_PER_CAUSE = 16;

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            BindException.class,
            ConstraintViolationException.class,
            HandlerMethodValidationException.class,
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleValidation(Exception exception) {
        if (exception instanceof MethodArgumentNotValidException ex) {
            return validationResponse(ex.getBindingResult().getFieldErrors().stream()
                    .map(this::formatFieldError).findFirst().orElse("请求参数不合法"));
        }
        if (exception instanceof BindException ex) {
            return validationResponse(ex.getBindingResult().getFieldErrors().stream()
                    .map(this::formatFieldError).findFirst().orElse("请求参数不合法"));
        }
        if (exception instanceof ConstraintViolationException ex) {
            return validationResponse(ex.getConstraintViolations().stream()
                    .map(violation -> violation.getPropertyPath() + "：" + violation.getMessage())
                    .findFirst().orElse("请求参数不合法"));
        }
        if (exception instanceof HttpMessageNotReadableException) {
            return validationResponse("请求体格式不正确，请检查 JSON 内容");
        }
        if (exception instanceof MissingServletRequestParameterException ex) {
            return validationResponse("缺少请求参数：" + ex.getParameterName());
        }
        if (exception instanceof MethodArgumentTypeMismatchException ex) {
            return validationResponse("请求参数“" + ex.getName() + "”格式不正确");
        }
        return validationResponse("请求参数不合法");
    }

    private String formatFieldError(FieldError error) {
        return error.getField() + "：" + error.getDefaultMessage();
    }

    private ResponseEntity<ApiResponse<Void>> validationResponse(String message) {
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status())
                .body(ApiResponse.error(ErrorCode.VALIDATION_FAILED.name(), message));
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(
            org.springframework.web.servlet.resource.NoResourceFoundException exception) {
        return ResponseEntity.status(404)
                .body(ApiResponse.error("NOT_FOUND", "接口不存在：" + exception.getResourcePath()));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException exception) {
        // 依赖类错误保留业务侧给出的可读说明（如百炼调用失败原因摘要），便于排查；
        // 校验/冲突/不存在也回具体原因，否则前端只能显示"参数校验失败"，无法自助修正。
        ErrorCode code = exception.errorCode();
        if (code == ErrorCode.DEPENDENCY_UNAVAILABLE
                || code == ErrorCode.VALIDATION_FAILED
                || code == ErrorCode.CONFLICT
                || code == ErrorCode.RESOURCE_NOT_FOUND) {
            String detail = exception.getMessage();
            if (detail != null && !detail.isBlank() && !detail.equals(code.message())) {
                return ResponseEntity.status(code.status())
                        .body(ApiResponse.error(code.name(), detail));
            }
        }
        return response(code);
    }

    @ExceptionHandler(org.springframework.web.client.ResourceAccessException.class)
    public ResponseEntity<ApiResponse<Void>> handleDependencyDown(
            org.springframework.web.client.ResourceAccessException exception) {
        // ES(9202) / Milvus(19530) 这类外部检索后端没起来时，给出可执行的提示，
        // 而不是一句"服务器内部错误"让用户猜。
        log.warn("外部依赖不可用 ({})", safeDiagnostics(exception));
        return ResponseEntity.status(ErrorCode.DEPENDENCY_UNAVAILABLE.status())
                .body(ApiResponse.error(ErrorCode.DEPENDENCY_UNAVAILABLE.name(),
                        "检索后端不可用（ES:9202 / Milvus:19530 未启动）。"
                                + "上传、切片、归类、刷题不受影响；检索测试需要先启动这两个服务。"));
    }

    /**
     * 乐观锁冲突 → 409。
     *
     * <p>简历/会话/轮次都带 {@code @Version}：两个请求同时改同一行时，后提交的那个
     * 会拿到这个异常。它不是"服务器内部错误"——数据完全没坏，只是别人先改了。
     * 回 409 让前端提示「内容已在别处更新，请刷新后重试」，比 500 有用得多，
     * 也能让用户明白自己刚才那一下没生效。</p>
     */
    @ExceptionHandler({
            org.springframework.dao.OptimisticLockingFailureException.class,
            jakarta.persistence.OptimisticLockException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLock(Exception exception) {
        log.info("乐观锁冲突（并发修改同一行）：{}", exception.getClass().getSimpleName());
        return ResponseEntity.status(ErrorCode.CONFLICT.status())
                .body(ApiResponse.error(ErrorCode.CONFLICT.name(),
                        "内容已在别处被修改，请刷新后重试"));
    }

    /**
     * 唯一约束冲突 → 409。
     *
     * <p>注册时租户标识或邮箱重复，是最常被踩到的一种：请求本身没毛病，只是这条数据已经存在。
     * 让它掉进兜底的 500，用户只会看到「服务器内部错误」，既猜不到原因也没法自己纠正，
     * 所以单独拦下来回 409，前端就能提示「这个邮箱或工作区标识已经被用了」。</p>
     */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolation(Exception exception) {
        log.info("唯一约束冲突：{}", safeDiagnostics(exception));
        return ResponseEntity.status(ErrorCode.CONFLICT.status())
                .body(ApiResponse.error(ErrorCode.CONFLICT.name(),
                        "这条数据已经存在了，请检查是否重复提交，或换一个标识再试"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception) {
        log.error("Unhandled request failure ({})", safeDiagnostics(exception));
        return response(ErrorCode.INTERNAL_ERROR);
    }

    private String safeDiagnostics(Throwable failure) {
        StringBuilder diagnostics = new StringBuilder("traceId=")
                .append(MDC.get(TraceIdFilter.MDC_KEY));
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            diagnostics.append(", ")
                    .append(depth == 0 ? "exceptionType=" : "causeType=")
                    .append(current.getClass().getName());
            StackTraceElement[] frames = current.getStackTrace();
            int frameCount = Math.min(frames.length, MAX_FRAMES_PER_CAUSE);
            for (int index = 0; index < frameCount; index++) {
                diagnostics.append(System.lineSeparator())
                        .append("  at ")
                        .append(frames[index]);
            }
            current = current.getCause();
        }
        return diagnostics.toString();
    }

    private ResponseEntity<ApiResponse<Void>> response(ErrorCode errorCode) {
        return ResponseEntity.status(errorCode.status())
                .body(ApiResponse.error(errorCode.name(), errorCode.message()));
    }
}
