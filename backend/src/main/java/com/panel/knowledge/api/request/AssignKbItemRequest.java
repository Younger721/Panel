package com.panel.knowledge.api.request;

public record AssignKbItemRequest(
        String itemType,
        Long itemId,
        Boolean primary) {
}
