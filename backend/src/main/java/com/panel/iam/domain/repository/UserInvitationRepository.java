package com.panel.iam.domain.repository;

import com.panel.iam.domain.model.UserInvitation;

import java.util.Optional;

public interface UserInvitationRepository {
    UserInvitation save(UserInvitation invitation);
    Optional<UserInvitation> findByTokenHash(String tokenHash);
}
