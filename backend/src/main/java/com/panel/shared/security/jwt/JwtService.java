package com.panel.shared.security.jwt;

import com.panel.shared.security.CurrentActor;

public interface JwtService {

    TokenPair issue(CurrentActor actor);

    JwtClaims verify(String rawToken, TokenType expectedType);
}
