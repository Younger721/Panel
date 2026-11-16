package com.panel.iam.application.command;

public record LogoutCommand(String accessToken, String refreshToken) {
}
