package com.nova.shared.security.jwt;

import com.nova.shared.security.CurrentActor;

public interface JwtService {

    TokenPair issue(CurrentActor actor);

    JwtClaims verify(String rawToken, TokenType expectedType);
}
