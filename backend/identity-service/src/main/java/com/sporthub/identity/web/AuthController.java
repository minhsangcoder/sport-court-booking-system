package com.sporthub.identity.web;

import com.sporthub.common.dto.ApiResponse;
import com.sporthub.identity.config.IdentityProperties;
import com.sporthub.identity.security.AuthenticatedUser;
import com.sporthub.identity.service.AuthService;
import com.sporthub.identity.service.RequestMetadata;
import com.sporthub.identity.service.SessionTokens;
import com.sporthub.identity.web.dto.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {
    static final String REFRESH_COOKIE = "sporthub_refresh";

    private final AuthService authService;
    private final com.sporthub.identity.service.OwnerSignupService ownerSignup;
    private final IdentityProperties properties;

    @PostMapping(value="/register",consumes="application/json")
    public ResponseEntity<ApiResponse<RegisterResult>> register(
            @Valid @RequestBody RegisterRequest body, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created("Account created; check Mailpit for the verification email",
                        authService.register(body, RequestMetadata.from(request))));
    }

    @PostMapping(value="/register",consumes="multipart/form-data")
    public ResponseEntity<ApiResponse<RegisterResult>> registerOwner(
            @Valid @RequestPart("request") RegisterRequest body,
            @RequestHeader(value="Idempotency-Key",required=false) String key,
            @RequestPart(value="identityDocument",required=false) org.springframework.web.multipart.MultipartFile identity,
            @RequestPart(value="locationDocument",required=false) org.springframework.web.multipart.MultipartFile location,
            @RequestPart(value="facilityImage",required=false) org.springframework.web.multipart.MultipartFile image,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created("Account created; verify your contact before signing in",
                ownerSignup.register(body,key,identity,location,image,RequestMetadata.from(request))));
    }

    @PostMapping("/verify")
    public ApiResponse<VerificationResult> verify(
            @Valid @RequestBody VerificationRequest body, HttpServletRequest request) {
        return ApiResponse.success("Verification completed", authService.verify(body, RequestMetadata.from(request)));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResult>> login(
            @Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        return authResponse(authService.login(body, RequestMetadata.from(request)));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthResult>> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            HttpServletRequest request) {
        return authResponse(authService.refresh(refreshToken, RequestMetadata.from(request)));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            @AuthenticationPrincipal AuthenticatedUser principal,
            HttpServletRequest request) {
        authService.logout(
                refreshToken,
                principal == null ? null : principal.sessionId(),
                principal == null ? null : principal.userId(),
                RequestMetadata.from(request));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, expiredCookie().toString())
                .build();
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse<Void>> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest body, HttpServletRequest request) {
        authService.forgotPassword(body, RequestMetadata.from(request));
        return ResponseEntity.accepted().body(ApiResponse.success(
                "If the email belongs to a verified account, a reset code has been sent", null));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(
            @Valid @RequestBody ResetPasswordRequest body, HttpServletRequest request) {
        authService.resetPassword(body, RequestMetadata.from(request));
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<ApiResponse<AuthResult>> authResponse(SessionTokens tokens) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(tokens.refreshToken()).toString())
                .body(ApiResponse.success(tokens.response()));
    }

    private ResponseCookie refreshCookie(String token) {
        return ResponseCookie.from(REFRESH_COOKIE, token)
                .httpOnly(true)
                .secure(properties.secureCookie())
                .sameSite("Lax")
                .path("/api/v1/auth")
                .maxAge(Duration.ofDays(7))
                .build();
    }

    private ResponseCookie expiredCookie() {
        return ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true)
                .secure(properties.secureCookie())
                .sameSite("Lax")
                .path("/api/v1/auth")
                .maxAge(Duration.ZERO)
                .build();
    }
}
