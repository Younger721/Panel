package com.panel.knowledge.api.request;

/**
 * 移动目录。
 *
 * @param parentId 新父目录；null 或 0 表示移到根
 * @param beforeId 插到哪个兄弟节点之前；null 表示放到末尾（只影响同级顺序，不改变层级）
 */
public record MoveKbCategoryRequest(Long parentId, Long beforeId) {
}
