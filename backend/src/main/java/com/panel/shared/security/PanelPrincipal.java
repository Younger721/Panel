package com.panel.shared.security;

import java.security.Principal;
import java.util.Objects;

public record PanelPrincipal(CurrentActor actor) implements Principal {

    public PanelPrincipal {
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
        return "PanelPrincipal[userId=" + actor.userId()
                + ", tenantId=" + actor.tenantId() + "]";
    }
}
