package com.sporthub.identity.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record ResetPasswordRequest(
        @NotNull UUID challengeId,
        @NotBlank @Pattern(regexp = "^[0-9]{6}$") String code,
        @NotBlank @Size(min = 8, max = 72) String newPassword) {
}
