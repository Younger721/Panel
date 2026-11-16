package com.panel.knowledge.api.request;

/** 把某个切片/题目移动到目标目录；categoryId 为空或 -1 表示移出所有目录。 */
public record MoveKbItemRequest(
        String itemType,
        Long itemId,
        Long categoryId) {
}
