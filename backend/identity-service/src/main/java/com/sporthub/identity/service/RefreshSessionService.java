package com.sporthub.identity.service;

import com.sporthub.identity.domain.AccountStatus;
import com.sporthub.identity.domain.RefreshToken;
import com.sporthub.identity.domain.User;
import com.sporthub.identity.exception.IdentityException;
import com.sporthub.identity.repository.RefreshTokenRepository;
import com.sporthub.identity.security.JwtService;
import com.sporthub.identity.web.dto.AuthResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RefreshSessionService {
    private final RefreshTokenRepository repository;
    private final SecretHashingService secrets;
    private final JwtService jwtService;
    private final UserViewMapper userViewMapper;

    @Transactional
    public void revokeAll(UUID userId) {
        repository.findByUserIdAndRevokedFalse(userId).forEach(session -> {
            session.setRevoked(true);
            session.setRevokedAt(Instant.now());
        });
    }

    @Transactional
    public SessionTokens issue(User user, RequestMetadata metadata) {
        return issue(user, metadata, null);
    }

    @Transactional
    public SessionTokens rotate(String rawRefreshToken, RequestMetadata metadata) {
        RefreshToken current = repository.findWithLockByTokenHash(secrets.sha256(rawRefreshToken))
                .orElseThrow(this::invalidRefreshToken);
        Instant now = Instant.now();
        if (!current.isActiveAt(now) || current.getUser().getStatus() != AccountStatus.ACTIVE) {
            throw invalidRefreshToken();
        }
        current.setRevoked(true);
        current.setRevokedAt(now);
        current.setLastUsedAt(now);
        return issue(current.getUser(), metadata, current.getId());
    }

    @Transactional
    public void revoke(String rawRefreshToken, UUID accessSessionId) {
        RefreshToken current = rawRefreshToken != null && !rawRefreshToken.isBlank()
                ? repository.findWithLockByTokenHash(secrets.sha256(rawRefreshToken)).orElse(null)
                : accessSessionId == null ? null : repository.findWithLockById(accessSessionId).orElse(null);
        if (current != null && !current.isRevoked()) {
            current.setRevoked(true);
            current.setRevokedAt(Instant.now());
        }
    }

    private SessionTokens issue(User user, RequestMetadata metadata, UUID rotatedFromId) {
        String rawRefreshToken = secrets.randomToken();
        RefreshToken session = repository.save(RefreshToken.builder()
                .user(user)
                .tokenHash(secrets.sha256(rawRefreshToken))
                .deviceInfo(metadata.userAgent())
                .ipAddress(metadata.ipAddress())
                .expiresAt(Instant.now().plusMillis(jwtService.refreshTokenLifetimeMillis()))
                .rotatedFromId(rotatedFromId)
                .build());
        String accessToken = jwtService.createAccessToken(user, session.getId());
        AuthResult response = new AuthResult(
                accessToken,
                "Bearer",
                jwtService.accessTokenLifetimeSeconds(),
                userViewMapper.toView(user));
        return new SessionTokens(response, rawRefreshToken);
    }

    private IdentityException invalidRefreshToken() {
        return new IdentityException(HttpStatus.UNAUTHORIZED, "IDENTITY-REFRESH-INVALID", "Refresh token is invalid or expired");
    }
}
