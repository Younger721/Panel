package com.panel.knowledge.api;

import com.panel.knowledge.api.request.PublicationRequest;
import com.panel.knowledge.application.KbChunkPage;
import com.panel.knowledge.application.KbChunkView;
import com.panel.knowledge.application.KbDocumentPage;
import com.panel.knowledge.application.KbDocumentKindScanService;
import com.panel.knowledge.application.KbDocumentService;
import com.panel.knowledge.application.KbDocumentView;
import com.panel.shared.api.ApiResponse;
import com.panel.shared.error.BusinessException;
import com.panel.shared.error.ErrorCode;
import com.panel.shared.security.CurrentActor;
import com.panel.shared.security.CurrentActorProvider;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/v1/kb")
@SecurityRequirement(name = "bearerAuth")
public class KbDocumentController {

    private final KbDocumentService documents;
    private final KbDocumentKindScanService docKindScan;
    private final CurrentActorProvider actors;

    public KbDocumentController(KbDocumentService documents,
                                KbDocumentKindScanService docKindScan,
                                CurrentActorProvider actors) {
        this.documents = documents;
        this.docKindScan = docKindScan;
        this.actors = actors;
    }

    @PostMapping(value = "/{kbId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<KbDocumentView> upload(@PathVariable Long kbId, @RequestPart("file") MultipartFile file) {
        CurrentActor actor = require("knowledge:write");
        return ApiResponse.success(documents.upload(kbId, file, actor.userId()));
    }

    @GetMapping("/{kbId}/documents")
    public ApiResponse<KbDocumentPage> list(@PathVariable Long kbId,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        require("knowledge:read");
        return ApiResponse.success(documents.list(kbId, status, keyword, page, size));
    }

    @GetMapping("/documents/{documentId}")
    public ApiResponse<KbDocumentView> get(@PathVariable Long documentId) {
        require("knowledge:read");
        return ApiResponse.success(documents.get(documentId));
    }

    @GetMapping("/chunks/{chunkId}")
    public ApiResponse<KbChunkView> chunk(@PathVariable Long chunkId) {
        require("knowledge:read");
        return ApiResponse.success(documents.chunk(chunkId));
    }

    @GetMapping("/documents/{documentId}/chunks")
    public ApiResponse<KbChunkPage> chunks(@PathVariable Long documentId,
                                           @RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        require("knowledge:read");
        return ApiResponse.success(documents.chunks(documentId, page, size));
    }

    @PostMapping("/documents/{documentId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<KbDocumentView> retry(@PathVariable Long documentId) {
        require("knowledge:write");
        return ApiResponse.success(documents.retry(documentId));
    }

    @PostMapping("/documents/{documentId}/reindex")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<KbDocumentView> reindex(@PathVariable Long documentId) {
        require("knowledge:write");
        return ApiResponse.success(documents.reindex(documentId));
    }

    /**
     * 存量文档类型重判：把题库库里混着的讲义/README/口播稿挑出来，删掉它们的"题"并转成切片归类。
     *
     * <p>默认干跑（dryRun=true），先看会删哪些、删几道。</p>
     */
    @PostMapping("/{kbId}/documents/kind-scan")
    public ApiResponse<KbDocumentKindScanService.ScanResult> kindScan(
            @PathVariable Long kbId,
            @RequestParam(defaultValue = "true") boolean dryRun) {
        require("knowledge:write");
        return ApiResponse.success(docKindScan.scan(kbId, dryRun));
    }

    @PatchMapping("/documents/{documentId}/publication")
    public ApiResponse<KbDocumentView> publication(@PathVariable Long documentId,
                                                   @Valid @RequestBody PublicationRequest request) {
        require("knowledge:write");
        return ApiResponse.success(documents.publish(documentId, request.published()));
    }

    @DeleteMapping("/documents/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long documentId) {
        require("knowledge:write");
        documents.delete(documentId);
    }

    @GetMapping("/documents/{documentId}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long documentId) {
        require("knowledge:read");
        KbDocumentView view = documents.get(documentId);
        byte[] content = documents.download(documentId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(view.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(content);
    }

    private CurrentActor require(String permission) {
        CurrentActor actor = actors.requireCurrent();
        if (!actor.hasPermission(permission)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return actor;
    }
}
