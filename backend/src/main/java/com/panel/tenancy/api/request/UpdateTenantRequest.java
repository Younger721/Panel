package com.panel.tenancy.api.request;

import com.panel.tenancy.application.command.UpdateTenantCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateTenantRequest(
        @NotBlank @Size(max = 128) String tenantName) {

    public UpdateTenantCommand toCommand() {
        return new UpdateTenantCommand(tenantName);
    }
}
