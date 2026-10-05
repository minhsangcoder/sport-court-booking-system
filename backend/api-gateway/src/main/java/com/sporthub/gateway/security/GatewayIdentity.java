package com.sporthub.gateway.security;

import java.util.List;
import java.util.UUID;

/**
 * Infrastructure identity produced from a gateway-validated access token.
 */
public record GatewayIdentity(
        UUID userId,
        String email,
        List<String> roles) {

    public GatewayIdentity {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }
}
