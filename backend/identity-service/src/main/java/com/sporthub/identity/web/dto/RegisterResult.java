package com.sporthub.identity.web.dto;

import com.sporthub.identity.domain.AccountStatus;

import java.time.Instant;
import java.util.UUID;

public record RegisterResult(
        UUID userId,
        AccountStatus accountStatus,
        UUID verificationChallengeId,
        Instant verificationExpiresAt) {
}
