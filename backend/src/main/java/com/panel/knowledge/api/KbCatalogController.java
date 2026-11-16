package com.panel.knowledge.api;

import com.panel.knowledge.api.request.AssignKbItemRequest;
import com.panel.knowledge.api.request.ConfirmKbItemsRequest;
import com.panel.knowledge.api.request.CreateKbCategoryRequest;
import com.panel.knowledge.api.request.MoveKbCategoryRequest;
import com.panel.knowledge.api.request.MoveKbItemRequest;
import com.panel.knowledge.api.request.UpdateKbCategoryRequest;
import com.panel.knowledge.application.KbCatalogNode;
import com.panel.knowledge.application.KbCatalogPage;
import com.panel.knowledge.application.KbCatalogService;
import com.panel.knowledge.application.KbAutoClassifyService;
import com.panel.knowledge.application.KbCategoryView;
import com.panel.shared.api.ApiResponse;
import com.panel.shared.error.BusinessException;
import com.panel.shared.error.ErrorCode;
import com.panel.shared.security.CurrentActor;
import com.panel.shared.security.CurrentActorProvider;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 知识目录：树、节点管理、条目挂载。 */
@RestController
@RequestMapping("/api/v1/kb")
@SecurityRequirement(name = "bearerAuth")
public class KbCatalogController {

    private final KbCatalogService catalog;
    private final KbAutoClassifyService autoClassify;
    private final CurrentActorProvider actors;

    public KbCatalogController(KbCatalogService catalog,
                               KbAutoClassifyService autoClassify,
                               CurrentActorProvider actors) {
        this.catalog = catalog;
        this.autoClassify = autoClassify;
        this.actors = actors;
    }

    @GetMapping("/{kbId}/catalog/tree")
    public ApiResponse<List<KbCatalogNode>> tree(@PathVariable Long kbId) {
        require("knowledge:read");
        return ApiResponse.success(catalog.tree(kbId));
    }

    @PostMapping("/{kbId}/catalog/classify")
    public ApiResponse<KbAutoClassifyService.KbClassifyResult> classify(@PathVariable Long kbId) {
        require("knowledge:write");
        return ApiResponse.success(autoClassify.classifyUncategorized(kbId));
    }

    /**
     * 按文档一键归类：只处理这份文档里还没挂知识点（或低置信）的题目/切片。
     *
     * <p>{@code redoLowConfidence=true} 时先把未锁定的低置信挂载清掉再重跑，
     * 让"模型当初没把握"的那批内容有一次重新归类的机会。</p>
     */
    @PostMapping("/{kbId}/catalog/documents/{documentId}/classify")
    public ApiResponse<KbAutoClassifyService.KbClassifyResult> classifyDocument(
            @PathVariable Long kbId,
            @PathVariable Long documentId,
            @RequestParam(defaultValue = "false") boolean redoLowConfidence) {
        require("knowledge:write");
        return ApiResponse.success(autoClassify.classifyDocument(kbId, documentId, redoLowConfidence));
    }

    /** 从另一个库继承目录树（按 path_key 去重），用于题库库补齐知识树。 */
    @PostMapping("/{kbId}/catalog/import")
    public ApiResponse<Map<String, Object>> importTree(@PathVariable Long kbId,
                                                       @RequestParam Long sourceKbId) {
        require("knowledge:write");
        return ApiResponse.success(catalog.importTree(kbId, sourceKbId));
    }

    @PostMapping("/{kbId}/catalog/categories")
    public ApiResponse<KbCategoryView> create(@PathVariable Long kbId,
                                              @RequestBody CreateKbCategoryRequest request) {
        require("knowledge:write");
        return ApiResponse.success(catalog.create(kbId, request.parentId(), request.name(),
                request.description(), request.pathKey()));
    }

    @PatchMapping("/catalog/categories/{categoryId}")
    public ApiResponse<KbCategoryView> rename(@PathVariable Long categoryId,
                                              @RequestBody UpdateKbCategoryRequest request) {
        require("knowledge:write");
        return ApiResponse.success(catalog.rename(categoryId, request.name(), request.description()));
    }

    @PostMapping("/catalog/categories/{categoryId}/move")
    public ApiResponse<KbCategoryView> move(@PathVariable Long categoryId,
                                            @RequestBody MoveKbCategoryRequest request) {
        require("knowledge:write");
        return ApiResponse.success(catalog.move(categoryId, request.parentId(), request.beforeId()));
    }

    /**
     * 删除目录。
     *
     * <p>{@code cascade=true} 连整棵子树一起删；子树上的题目/切片只摘挂载（回到"未分类"），不会删内容本体。</p>
     */
    @DeleteMapping("/catalog/categories/{categoryId}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable Long categoryId,
                                                   @RequestParam(defaultValue = "false") boolean cascade) {
        require("knowledge:write");
        catalog.delete(categoryId, cascade);
        return ApiResponse.success(Map.of("deleted", true, "cascade", cascade));
    }

    @GetMapping("/catalog/categories/{categoryId}/items")
    public ApiResponse<KbCatalogPage> items(@PathVariable Long categoryId,
                                            @RequestParam Long kbId,
                                            @RequestParam(defaultValue = "CHUNK") String type,
                                            @RequestParam(defaultValue = "0") int offset,
                                            @RequestParam(defaultValue = "20") int limit) {
        require("knowledge:read");
        return ApiResponse.success(catalog.items(kbId, categoryId, type, offset, limit));
    }

    @PostMapping("/{kbId}/catalog/items/move")
    public ApiResponse<Map<String, Object>> moveItem(@PathVariable Long kbId,
                                                     @RequestBody MoveKbItemRequest request) {
        require("knowledge:write");
        catalog.moveItem(kbId, request.itemType(), request.itemId(), request.categoryId());
        return ApiResponse.success(Map.of("moved", true));
    }

    @PostMapping("/catalog/categories/{categoryId}/items")
    public ApiResponse<Map<String, Object>> assign(@PathVariable Long categoryId,
                                                   @RequestBody AssignKbItemRequest request) {
        require("knowledge:write");
        catalog.assign(categoryId, request.itemType(), request.itemId(), request.primary());
        return ApiResponse.success(Map.of("assigned", true));
    }

    /** 批量确认：一口气把当前页的低置信条目锁掉。 */
    @PostMapping("/{kbId}/catalog/items/confirm-batch")
    public ApiResponse<Map<String, Object>> confirmBatch(@PathVariable Long kbId,
                                                        @RequestBody ConfirmKbItemsRequest request) {
        require("knowledge:write");
        return ApiResponse.success(catalog.confirmBatch(kbId, request.itemType(), request.itemIds()));
    }

    /** 全库一键确认：把该类型所有"自动挂的、没确认过"的归属钉成人工真值。 */
    @PostMapping("/{kbId}/catalog/items/confirm-all")
    public ApiResponse<Map<String, Object>> confirmAll(@PathVariable Long kbId,
                                                       @RequestParam(defaultValue = "CHUNK") String itemType) {
        require("knowledge:write");
        return ApiResponse.success(catalog.confirmAll(kbId, itemType));
    }

    @PostMapping("/{kbId}/catalog/items/{itemType}/{itemId}/confirm")
    public ApiResponse<Map<String, Object>> confirm(@PathVariable Long kbId,
                                                    @PathVariable String itemType,
                                                    @PathVariable Long itemId) {
        require("knowledge:write");
        int confirmed = catalog.confirm(kbId, itemType, itemId);
        return ApiResponse.success(Map.of("confirmed", confirmed));
    }

    @DeleteMapping("/catalog/categories/{categoryId}/items/{itemType}/{itemId}")
    public ApiResponse<Map<String, Object>> unassign(@PathVariable Long categoryId,
                                                     @PathVariable String itemType,
                                                     @PathVariable Long itemId) {
        require("knowledge:write");
        catalog.unassign(categoryId, itemType, itemId);
        return ApiResponse.success(Map.of("unassigned", true));
    }

    private void require(String permission) {
        CurrentActor actor = actors.requireCurrent();
        if (!actor.hasPermission(permission)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }
}
