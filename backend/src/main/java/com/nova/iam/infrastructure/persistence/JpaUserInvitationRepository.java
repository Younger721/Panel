package com.nova.iam.infrastructure.persistence;

import com.nova.iam.domain.model.UserInvitation;
import com.nova.iam.domain.repository.UserInvitationRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
class JpaUserInvitationRepository implements UserInvitationRepository {
    private final SpringDataUserInvitationRepository delegate;

    JpaUserInvitationRepository(SpringDataUserInvitationRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public UserInvitation save(UserInvitation invitation) {
        return delegate.save(invitation);
    }

    @Override
    public Optional<UserInvitation> findByTokenHash(String tokenHash) {
        return delegate.findByTokenHash(tokenHash);
    }
}
