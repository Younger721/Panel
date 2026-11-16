package com.panel.interview.api;

import com.panel.interview.application.ParsedResume;
import com.panel.interview.application.ResumeAccessGuard;
import com.panel.interview.application.ResumeAnalysisService;
import com.panel.interview.application.ResumePdfExportService;
import com.panel.interview.application.ResumePreviewService;
import com.panel.interview.application.ResumeService;
import com.panel.interview.application.ResumeService.ResumeView;
import com.panel.shared.api.ApiResponse;
import com.panel.shared.error.BusinessException;
import com.panel.shared.error.ErrorCode;
import com.panel.shared.security.CurrentActor;
import com.panel.shared.security.CurrentActorProvider;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 简历接口：上传 → 轮询状态 → 取结构化画像。
 *
 * <p>上传返回 202 而不是 200：解析是异步的，响应只代表"已接收"。
 * 客户端拿到 id 后轮询 {@code GET /{id}}，状态从 PENDING 走到 PARSED/FAILED。</p>
 *
 * <p>所有 {@code /{id}} 接口先过 {@link ResumeAccessGuard}：归属不符一律 404，
 * 不区分"不存在"和"不是你的"，避免把 id 空间当探测面。</p>
 */
@RestController
@RequestMapping("/api/v1/interview/resumes")
@SecurityRequirement(name = "bearerAuth")
public class ResumeController {

    /** 与解析上限一致：先用 getSize() 拦，别把 20MB 整个读进堆里再拒绝。 */
    private static final long MAX_UPLOAD_BYTES = 10L * 1024 * 1024;

    private final ResumeService service;
    private final ResumeAnalysisService analysisService;
    private final ResumePreviewService previewService;
    private final ResumePdfExportService exportService;
    private final ResumeAccessGuard access;
    private final CurrentActorProvider actors;

    public ResumeController(ResumeService service, ResumeAnalysisService analysisService,
                            ResumePreviewService previewService, ResumePdfExportService exportService,
                            ResumeAccessGuard access, CurrentActorProvider actors) {
        this.service = service;
        this.analysisService = analysisService;
        this.previewService = previewService;
        this.exportService = exportService;
        this.access = access;
        this.actors = actors;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<ResumeView> upload(@RequestPart("file") MultipartFile file) throws IOException {
        require("resume:write");
        if (file.getSize() <= 0 || file.getSize() > MAX_UPLOAD_BYTES) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
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
        return ApiResponse.success(service.saveContent(id,
                body == null ? null : body.text(),
                body == null ? null : body.edits(),
                body == null ? null : body.format()));
    }

    /**
     * 读 Markdown 内容稿原文，给 Markdown 编辑器当初始内容。
     *
     * <p>204 表示这份简历还没有内容稿，前端退回用解析结果渲染出的 md 开个头。
     * 和 {@code /{id}/markdown}（渲染后的全文下载）不是一回事：那个是"给你看的"，
     * 这个是"给你接着改的"。</p>
     */
    @GetMapping(value = "/{id}/content/markdown", produces = MediaType.TEXT_MARKDOWN_VALUE)
    public ResponseEntity<String> markdownContent(@PathVariable Long id) {
        require("resume:read");
        String body = service.markdownContent(id);
        if (body == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_MARKDOWN).body(body);
    }

    /** 原文不变，只重跑解析与批注（模型升级，或上一次结果不满意）。 */
    @PostMapping("/{id}/analyze")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<ResumeView> reanalyze(@PathVariable Long id) {
        require("resume:write");
        return ApiResponse.success(service.reanalyze(id));
    }

    /**
     * 解卡：把一份长期停在解析中/分析中的简历退回可重试状态。
     *
     * <p>解析任务被异常中断时（进程重启、内容变更丢弃结果），状态机可能停在
     * PARSING/ANALYZING，此时保存与重新分析都会被 409 挡住，用户只能删掉重传。
     * 这个接口给 UI 一个"重试"入口。</p>
     */
    @PostMapping("/{id}/reset")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<ResumeView> reset(@PathVariable Long id) {
        require("resume:write");
        return ApiResponse.success(service.reset(id));
    }

    /**
     * html 目前只用于日志/排查，落库的是纯文本；edits 是 PDF 原位编辑的行改动
     * （行索引 → 新文本），只用来还原编辑器里的显形文字。
     *
     * <p>format 决定这次保存改的是"原件"还是"内容稿"：{@code markdown} 时正文按
     * text/markdown 落到 md 稿上，PDF 原版与 edit_layer 都保留；其它值走原来那条
     * 覆盖原件的纯文本通路。</p>
     */
    public record ContentUpdate(String text, String html, Map<String, String> edits, String format) {
    }

    @GetMapping("/{id}")
    public ApiResponse<ResumeView> find(@PathVariable Long id) {
        require("resume:read");
        access.require(id);
        return ApiResponse.success(service.find(id));
    }

    /**
     * 软删除：写 {@code deleted_at}，不删行、不清对象存储、不级联删画像与批注。
     *
     * <p>返回 200 而不是 204：响应体里的 {@code deletedAt} 是前端撤销提示的依据，
     * 顺手也把"删的是哪一条"钉死在返回里。</p>
     */
    @DeleteMapping("/{id}")
    public ApiResponse<ResumeView> delete(@PathVariable Long id) {
        require("resume:write");
        return ApiResponse.success(service.delete(id));
    }

    /** 撤销软删除，记录与画像原样回到列表。 */
    @PostMapping("/{id}/restore")
    public ApiResponse<ResumeView> restore(@PathVariable Long id) {
        require("resume:write");
        return ApiResponse.success(service.restore(id));
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
        access.require(id);
        return ApiResponse.success(analysisService.view(id));
    }

    /** 原版预览：只返回页面尺寸，图片按页单独取。 */
    @GetMapping("/{id}/preview")
    public ApiResponse<ResumePreviewService.PreviewView> preview(@PathVariable Long id) {
        require("resume:read");
        access.require(id);
        return ApiResponse.success(previewService.preview(id));
    }

    /** 文字图层：每页尺寸 + 逐行文本与坐标，供"PDF 原样编辑"用。 */
    @GetMapping("/{id}/preview/text")
    public ApiResponse<ResumePreviewService.TextLayerView> previewText(@PathVariable Long id) {
        require("resume:read");
        access.require(id);
        return ApiResponse.success(previewService.textLayer(id));
    }

    /** 单页渲染图（PNG）。按内容哈希缓存，滚动多页也不会重复渲染。 */
    @GetMapping(value = "/{id}/preview/pages/{page}", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> previewPage(@PathVariable Long id,
                                              @PathVariable int page,
                                              @RequestParam(name = "scale", defaultValue = "2.5") float scale) {
        require("resume:read");
        access.require(id);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
                .body(previewService.pageImage(id, page, scale));
    }

    /**
     * 导出改完的 PDF：原件版面 + 编辑器的文字改动，直接当附件下载。
     *
     * <p>无状态、不落库：每次导出都按当前 {@code edit_layer} 现算，所以「改了没保存」不会
     * 悄悄混进下载文件里——前端会先保存再导出。</p>
     */
    @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> exportPdf(@PathVariable Long id) {
        require("resume:read");
        access.require(id);
        byte[] pdf = exportService.export(id);
        String fileName = URLEncoder.encode(exportService.exportFileName(id), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"resume-edited.pdf\"; filename*=UTF-8''" + fileName)
                .body(pdf);
    }

    /** 简历里嵌的字体文件，给编辑框当网页字体用，字形才和原件一致。 */
    @GetMapping("/{id}/preview/fonts/{family}")
    public ResponseEntity<byte[]> previewFont(@PathVariable Long id, @PathVariable String family) {
        require("resume:read");
        access.require(id);
        ResumePreviewService.FontFile file = previewService.fontFile(id, family);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePrivate())
                .body(file.data());
    }

    /** 把分析里的 quote 批量映射成页面矩形，供前端画高亮框。 */
    @PostMapping("/{id}/preview/locate")
    public ApiResponse<Map<String, ResumePreviewService.HighlightView>> locate(
            @PathVariable Long id,
            @RequestBody ResumePreviewService.LocateRequest request) {
        require("resume:read");
        access.require(id);
        List<ResumePreviewService.QuoteRequest> quotes = request == null ? List.of() : request.quotes();
        return ApiResponse.success(previewService.locate(id, quotes));
    }

    /** 下载转换后的 Markdown 原文。 */
    @GetMapping(value = "/{id}/markdown", produces = MediaType.TEXT_MARKDOWN_VALUE)
    public ResponseEntity<String> markdown(@PathVariable Long id) {
        require("resume:read");
        access.require(id);
        return ResponseEntity.ok()
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
