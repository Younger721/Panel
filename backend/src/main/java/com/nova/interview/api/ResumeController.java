package com.nova.interview.api;

import com.nova.interview.application.ParsedResume;
import com.nova.interview.application.ResumeAnalysisService;
import com.nova.interview.application.ResumePreviewService;
import com.nova.interview.application.ResumeService;
import com.nova.interview.application.ResumeService.ResumeView;
import com.nova.shared.api.ApiResponse;
import com.nova.shared.error.BusinessException;
import com.nova.shared.error.ErrorCode;
import com.nova.shared.security.CurrentActor;
import com.nova.shared.security.CurrentActorProvider;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 简历接口：上传 → 轮询状态 → 取结构化画像。
 *
 * <p>上传返回 202 而不是 200：解析是异步的，响应只代表"已接收"。
 * 客户端拿到 id 后轮询 {@code GET /{id}}，状态从 PENDING 走到 PARSED/FAILED。</p>
 */
@RestController
@RequestMapping("/api/v1/interview/resumes")
@SecurityRequirement(name = "bearerAuth")
public class ResumeController {

    private final ResumeService service;
    private final ResumeAnalysisService analysisService;
    private final ResumePreviewService previewService;
    private final CurrentActorProvider actors;

    public ResumeController(ResumeService service, ResumeAnalysisService analysisService,
                            ResumePreviewService previewService, CurrentActorProvider actors) {
        this.service = service;
        this.analysisService = analysisService;
        this.previewService = previewService;
        this.actors = actors;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<ResumeView> upload(@RequestPart("file") MultipartFile file) throws IOException {
        require("resume:write");
        return ApiResponse.success(service.upload(file.getOriginalFilename(), file.getContentType(), file.getBytes()));
    }

    @GetMapping
    public ApiResponse<List<ResumeView>> list() {
        require("resume:read");
        return ApiResponse.success(service.list());
    }

    /**
     * 编辑器保存：把改好的正文写回成新的原文，并重新排队解析。
     *
     * <p>202 而不是 200——解析分析都是异步的，这之后状态会从 PENDING 再走一遍。</p>
     */
    @PutMapping("/{id}/content")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<ResumeView> updateContent(@PathVariable Long id, @RequestBody ContentUpdate body) {
        require("resume:write");
        return ApiResponse.success(service.saveContent(id, body == null ? null : body.text()));
    }

    /** 原文不变，只重跑解析与批注（模型升级，或上一次结果不满意）。 */
    @PostMapping("/{id}/analyze")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<ResumeView> reanalyze(@PathVariable Long id) {
        require("resume:write");
        return ApiResponse.success(service.reanalyze(id));
    }

    /** html 目前只用于日志/排查，落库的是纯文本。 */
    public record ContentUpdate(String text, String html) {
    }

    @GetMapping("/{id}")
    public ApiResponse<ResumeView> find(@PathVariable Long id) {
        require("resume:read");
        return ApiResponse.success(service.find(id));
    }

    @GetMapping("/{id}/profile")
    public ApiResponse<ParsedResume> profile(@PathVariable Long id) {
        require("resume:read");
        return ApiResponse.success(service.profile(id));
    }

    /** 批注页数据源：Markdown + 块结构 + 模块/小点/点评。 */
    @GetMapping("/{id}/analysis")
    public ApiResponse<ResumeAnalysisService.AnalysisView> analysis(@PathVariable Long id) {
        require("resume:read");
        return ApiResponse.success(analysisService.view(id));
    }

    /** 原版预览：只返回页面尺寸，图片按页单独取。 */
    @GetMapping("/{id}/preview")
    public ApiResponse<ResumePreviewService.PreviewView> preview(@PathVariable Long id) {
        require("resume:read");
        return ApiResponse.success(previewService.preview(id));
    }

    /** 单页渲染图（PNG）。按内容哈希缓存，滚动多页也不会重复渲染。 */
    @GetMapping(value = "/{id}/preview/pages/{page}", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> previewPage(@PathVariable Long id,
                                              @PathVariable int page,
                                              @RequestParam(name = "scale", defaultValue = "2.5") float scale) {
        require("resume:read");
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
                .body(previewService.pageImage(id, page, scale));
    }

    /** 把分析里的 quote 批量映射成页面矩形，供前端画高亮框。 */
    @PostMapping("/{id}/preview/locate")
    public ApiResponse<Map<String, ResumePreviewService.HighlightView>> locate(
            @PathVariable Long id,
            @RequestBody ResumePreviewService.LocateRequest request) {
        require("resume:read");
        List<ResumePreviewService.QuoteRequest> quotes = request == null ? List.of() : request.quotes();
        return ApiResponse.success(previewService.locate(id, quotes));
    }

    /** 下载转换后的 Markdown 原文。 */
    @GetMapping(value = "/{id}/markdown", produces = MediaType.TEXT_MARKDOWN_VALUE)
    public org.springframework.http.ResponseEntity<String> markdown(@PathVariable Long id) {
        require("resume:read");
        return org.springframework.http.ResponseEntity.ok()
                .contentType(MediaType.TEXT_MARKDOWN)
                .body(analysisService.markdown(id));
    }

    private void require(String permission) {
        CurrentActor actor = actors.requireCurrent();
        if (!actor.hasPermission(permission)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }
}
