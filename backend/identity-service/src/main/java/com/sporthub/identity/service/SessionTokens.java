package com.sporthub.identity.service;

import com.sporthub.identity.web.dto.AuthResult;

public record SessionTokens(AuthResult response, String refreshToken) {
}
