package com.sporthub.identity.web.dto;

import com.sporthub.identity.domain.AccountStatus;

import java.util.UUID;

public record VerificationResult(UUID userId, boolean verified, AccountStatus accountStatus) {
}
