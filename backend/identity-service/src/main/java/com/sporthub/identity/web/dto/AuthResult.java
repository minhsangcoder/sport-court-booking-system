package com.sporthub.identity.web.dto;

public record AuthResult(
        String accessToken,
        String tokenType,
        int expiresIn,
        UserProfileView user) {
}
