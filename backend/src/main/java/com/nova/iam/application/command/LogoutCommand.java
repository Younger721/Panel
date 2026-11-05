package com.nova.iam.application.command;

public record LogoutCommand(String accessToken, String refreshToken) {
}
