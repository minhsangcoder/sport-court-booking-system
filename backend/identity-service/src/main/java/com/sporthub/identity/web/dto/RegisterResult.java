package com.sporthub.identity.web.dto;

import com.sporthub.identity.domain.AccountStatus;

import java.time.Instant;
import java.util.UUID;

public record RegisterResult(
        UUID userId,
        AccountStatus accountStatus,
        UUID verificationChallengeId,
        Instant verificationExpiresAt,
        OwnerApplicationDtos.Summary ownerApplication,
        String ownerSetupMessage) {
    public RegisterResult(UUID userId,AccountStatus accountStatus,UUID verificationChallengeId,Instant verificationExpiresAt){this(userId,accountStatus,verificationChallengeId,verificationExpiresAt,null,null);}

}
