package com.sporthub.identity.security;

import com.sporthub.identity.domain.Role;

import java.util.Set;
import java.util.UUID;

public record AuthenticatedUser(UUID userId, String email, Set<Role> roles, UUID sessionId) {
}
