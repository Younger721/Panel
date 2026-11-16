package com.panel.iam.api.request;

import com.panel.iam.domain.model.RoleCode;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.Set;

public record ReplaceUserRolesRequest(@NotEmpty Set<@NotNull RoleCode> roles) {
}
