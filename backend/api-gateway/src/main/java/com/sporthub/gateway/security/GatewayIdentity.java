package com.sporthub.gateway.security;

import java.util.List;
import java.util.UUID;

/**
 * Infrastructure identity produced by a trusted authentication resolver.
 * Phase 1 intentionally supplies only a no-op resolver; Phase 2 will validate JWTs.
 */
public record GatewayIdentity(
        UUID userId,
        String email,
        String role,
        List<String> permissions,
        List<UUID> facilityIds) {

    public GatewayIdentity {
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
        facilityIds = facilityIds == null ? List.of() : List.copyOf(facilityIds);
    }
}
