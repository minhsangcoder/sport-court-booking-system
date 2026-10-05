package com.sporthub.identity.web.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record VerificationRequest(
        @Size(min = 32, max = 2048) String verificationToken,
        UUID challengeId,
        @Pattern(regexp = "^[0-9]{6}$") String code) {
}
