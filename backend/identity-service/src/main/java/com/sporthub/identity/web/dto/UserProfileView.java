package com.sporthub.identity.web.dto;

import com.sporthub.identity.domain.AccountStatus;
import com.sporthub.identity.domain.Role;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

public record UserProfileView(
        UUID id,
        String fullName,
        String email,
        String phone,
        String avatarUrl,
        LocalDate dateOfBirth,
        Set<Role> roles,
        AccountStatus status,
        boolean emailVerified,
        boolean phoneVerified,
        Instant createdAt) {
}
