package com.panel.knowledge.api.request;

import java.util.List;

/** 批量确认归属：把 itemIds 里已挂目录的条目全部标成人工确认（locked）。 */
public record ConfirmKbItemsRequest(
        String itemType,
        List<Long> itemIds) {
}
