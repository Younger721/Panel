package com.panel.knowledge.application;

import java.util.List;

/** 目录下的条目：切片或题目统一成一张卡片。 */
public record KbCatalogItem(
        Long id,
        String itemType,
        String title,
        String excerpt,
        Long documentId,
        String documentName,
        Integer ordinal,
        Integer pageNo,
        Long questionId,
        String company,
        Integer year,
        String difficulty,
        List<String> tags,
        /** 这条内容挂在哪；{@code null} 说明还没挂（只出现在「未分类」里）。 */
        Long categoryId,
        /** 人工确认过 = 自动归类不再覆盖它。 */
        boolean locked,
        /** 模型给的可信度；人工挂载为 {@code null}。 */
        Double confidence,
        /** 挂载来源：LLM / RULE / MANUAL / MOVE / IMPORT。 */
        String linkSource) {

    /** 判定"需要人看一眼"：挂了但没确认，且是自动挂的。 */
    public boolean pendingConfirm() {
        return categoryId != null && !locked;
    }
}
