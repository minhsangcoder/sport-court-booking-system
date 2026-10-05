package com.sporthub.identity.repository;

import com.sporthub.identity.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    java.util.List<RefreshToken> findByUserIdAndRevokedFalse(UUID userId);
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshToken> findWithLockByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshToken> findWithLockById(UUID id);
}
