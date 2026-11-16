package com.panel.tenancy.application.command;

public record UpdateTenantSettingsCommand(
        Boolean reservationEnabled,
        Boolean checkInEnabled,
        String timezone) {
}
