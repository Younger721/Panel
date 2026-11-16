package com.panel.knowledge.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.panel.knowledge.application.KnowledgeDocumentUseCase;
import com.panel.knowledge.application.KnowledgeDocumentView;
import com.panel.knowledge.application.KnowledgeOnlyOfficeEditorView;
import com.panel.knowledge.application.KnowledgeOnlyOfficeService;
import com.panel.knowledge.application.UpdateKnowledgeDocumentCommand;
import com.panel.knowledge.application.UploadKnowledgeDocumentCommand;
import com.panel.knowledge.domain.model.DocumentVisibility;
import com.panel.iam.domain.model.RoleCode;
import com.panel.shared.api.ApiResponse;
import com.panel.shared.security.CurrentActorProvider;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import com.panel.knowledge.domain.model.KnowledgeType;

@RestController
@RequestMapping("/api/v1/knowledge/documents")
@SecurityRequirement(name = "bearerAuth")
@org.springframework.context.annotation.Profile("legacy-knowledge")
public class KnowledgeDocumentController {
    private final KnowledgeDocumentUseCase useCase;
    private final CurrentActorProvider actors;
    private final KnowledgeOnlyOfficeService onlyOffice;

    public KnowledgeDocumentController(
            KnowledgeDocumentUseCase useCase,
            CurrentActorProvider actors,
            KnowledgeOnlyOfficeService onlyOffice) {
        this.useCase = useCase;
        this.actors = actors;
        this.onlyOffice = onlyOffice;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<KnowledgeDocumentView> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam DocumentVisibility visibility,
            @RequestParam(defaultValue = "GENERAL") String category,
            @RequestParam(required = false) List<RoleCode> audienceRoles,
            @RequestParam(required = false) Long replaceDocumentId,
            @RequestParam(required = false) KnowledgeType knowledgeType,
            @RequestParam(required = false) String applicableProduct,
            @RequestParam(required = false) BigDecimal priceAmount,
            @RequestParam(required = false) String discountType,
            @RequestParam(required = false) BigDecimal discountValue,
            @RequestParam(required = false) LocalDateTime effectiveFrom,
            @RequestParam(required = false) LocalDateTime effectiveTo,
            @RequestParam(required = false) String conditions,
            @RequestParam(required = false) Integer promotionPriority) throws IOException {
        return ApiResponse.success(useCase.upload(actors.requireCurrent(),
                new UploadKnowledgeDocumentCommand(file.getOriginalFilename(), file.getContentType(),
                        file.getBytes(), visibility, category, audienceRoles, replaceDocumentId, knowledgeType,
                        applicableProduct, priceAmount, discountType, discountValue, effectiveFrom, effectiveTo, conditions, promotionPriority, null)));
    }

    @GetMapping
    public ApiResponse<List<KnowledgeDocumentView>> list() {
        return ApiResponse.success(useCase.list(actors.requireCurrent()));
    }

    @GetMapping("/{id}")
    public ApiResponse<KnowledgeDocumentView> find(@PathVariable Long id) {
        return ApiResponse.success(useCase.find(actors.requireCurrent(), id));
    }

    @PatchMapping("/{id}")
    public ApiResponse<KnowledgeDocumentView> update(
            @PathVariable Long id,
            @RequestParam DocumentVisibility visibility,
            @RequestParam(required = false) List<RoleCode> audienceRoles) {
        return ApiResponse.success(useCase.update(
                actors.requireCurrent(), id, new UpdateKnowledgeDocumentCommand(visibility, audienceRoles)));
    }

    @PatchMapping("/{id}/publication")
    public ApiResponse<KnowledgeDocumentView> publication(@PathVariable Long id, @RequestParam boolean published) {
        return ApiResponse.success(useCase.publish(actors.requireCurrent(), id, published));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        useCase.delete(actors.requireCurrent(), id);
    }

    @GetMapping("/{id}/editor-config")
    public ApiResponse<KnowledgeOnlyOfficeEditorView> editorConfig(@PathVariable Long id) {
        return ApiResponse.success(onlyOffice.buildEditorConfig(actors.requireCurrent(), id));
    }

    /** OnlyOffice Document Server 拉取原 docx（query token 鉴权） */
    @GetMapping("/{id}/content")
    public ResponseEntity<byte[]> content(
            @PathVariable Long id,
            @RequestParam String token,
            @RequestParam long userId) {
        byte[] bytes = onlyOffice.readContentForOnlyOffice(id, userId, token);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE,
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(bytes);
    }

    /** OnlyOffice 保存回调 */
    @PostMapping("/{id}/onlyoffice/callback")
    public Map<String, Object> onlyOfficeCallback(
            @PathVariable Long id,
            @RequestParam long userId,
            @RequestParam String session,
            @RequestBody JsonNode body) {
        return onlyOffice.handleCallback(id, userId, session, body);
    }
}
