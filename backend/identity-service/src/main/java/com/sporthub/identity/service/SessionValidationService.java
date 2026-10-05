package com.sporthub.identity.service;

import com.sporthub.identity.domain.AccountStatus;
import com.sporthub.identity.repository.RefreshTokenRepository;
import com.sporthub.identity.security.AuthenticatedUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

@Service
public class SessionValidationService {
    private final RefreshTokenRepository sessions;
    public SessionValidationService(RefreshTokenRepository sessions) { this.sessions = sessions; }

    @Transactional(readOnly = true)
    public AuthenticatedUser validate(AuthenticatedUser token) {
        var session = sessions.findById(token.sessionId()).orElseThrow(() -> new IllegalArgumentException("Session revoked"));
        var user = session.getUser();
        if (!session.isActiveAt(Instant.now()) || !user.getId().equals(token.userId()) || user.getStatus() != AccountStatus.ACTIVE)
            throw new IllegalArgumentException("Session revoked");
        return new AuthenticatedUser(user.getId(), user.getEmail(), java.util.Set.copyOf(user.getRoles()), session.getId());
    }
}
