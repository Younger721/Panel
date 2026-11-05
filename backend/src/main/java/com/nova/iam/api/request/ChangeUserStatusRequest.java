package com.nova.iam.api.request;

import com.nova.iam.domain.model.UserStatus;
import jakarta.validation.constraints.NotNull;

public record ChangeUserStatusRequest(@NotNull UserStatus status) {
}
