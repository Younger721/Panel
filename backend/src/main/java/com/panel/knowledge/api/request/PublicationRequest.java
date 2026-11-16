package com.panel.knowledge.api.request;

import jakarta.validation.constraints.NotNull;

public record PublicationRequest(@NotNull Boolean published) {
}
