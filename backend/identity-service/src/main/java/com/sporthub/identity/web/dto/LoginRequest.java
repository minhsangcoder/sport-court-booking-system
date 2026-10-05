package com.sporthub.identity.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Size(min = 3, max = 254) String identifier,
        @NotBlank @Size(max = 72) String password) {
}
