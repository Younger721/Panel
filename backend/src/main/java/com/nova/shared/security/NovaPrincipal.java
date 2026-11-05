package com.nova.shared.security;

import java.security.Principal;
import java.util.Objects;

public record NovaPrincipal(CurrentActor actor) implements Principal {

    public NovaPrincipal {
        Objects.requireNonNull(actor, "actor");
    }

    @Override
    public String getName() {
        return actor.userId().toString();
    }

    public String getCredentials() {
        return "";
    }

    @Override
    public String toString() {
        return "NovaPrincipal[userId=" + actor.userId()
                + ", tenantId=" + actor.tenantId() + "]";
    }
}
