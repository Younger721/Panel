package com.panel.shared.security;

import com.panel.shared.error.BusinessException;
import com.panel.shared.error.ErrorCode;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class SecurityContextCurrentActorProvider implements CurrentActorProvider {

    @Override
    public Optional<CurrentActor> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        if (!(authentication.getPrincipal() instanceof PanelPrincipal principal)) {
            return Optional.empty();
        }
        return Optional.of(principal.actor());
    }

    @Override
    public CurrentActor requireCurrent() {
        return current().orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
    }
}
