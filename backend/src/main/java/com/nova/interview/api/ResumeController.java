package com.nova.interview.api;

import com.nova.interview.application.ParsedResume;
import com.nova.interview.application.ResumeAnalysisService;
import com.nova.interview.application.ResumeService;
import com.nova.interview.application.ResumeService.ResumeView;
import com.nova.shared.api.ApiResponse;
import com.nova.shared.error.BusinessException;
import com.nova.shared.error.ErrorCode;
import com.nova.shared.security.CurrentActor;
import com.nova.shared.security.CurrentActorProvider;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

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
    private final CurrentActorProvider actors;

    public ResumeController(ResumeService service, ResumeAnalysisService analysisService,
                            CurrentActorProvider actors) {
        this.service = service;
        this.analysisService = analysisService;
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
