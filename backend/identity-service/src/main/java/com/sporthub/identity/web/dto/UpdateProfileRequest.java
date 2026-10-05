package com.sporthub.identity.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record UpdateProfileRequest(
        @Size(min = 2, max = 120) String fullName,
        @Email @Size(max = 254) String email,
        @Pattern(regexp = "^\\+[1-9][0-9]{7,14}$") String phone,
        @Size(max = 2048) String avatarUrl,
        LocalDate dateOfBirth) {
}
