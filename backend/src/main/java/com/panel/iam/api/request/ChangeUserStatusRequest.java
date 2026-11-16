package com.panel.iam.api.request;

import com.panel.iam.domain.model.UserStatus;
import jakarta.validation.constraints.NotNull;

public record ChangeUserStatusRequest(@NotNull UserStatus status) {
}
