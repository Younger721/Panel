package com.nova.iam.application.command;

public record AcceptInvitationCommand(String token, String password, String displayName) {
}
