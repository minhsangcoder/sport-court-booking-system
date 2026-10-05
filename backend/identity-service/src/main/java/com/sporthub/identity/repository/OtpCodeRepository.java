package com.sporthub.identity.repository;

import com.sporthub.identity.domain.OtpCode;
import com.sporthub.identity.domain.OtpPurpose;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OtpCodeRepository extends JpaRepository<OtpCode, UUID> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    java.util.Optional<OtpCode> findWithLockById(UUID id);
    List<OtpCode> findByUserIdAndPurposeAndVerifiedFalse(UUID userId, OtpPurpose purpose);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update OtpCode challenge
               set challenge.attempts = challenge.attempts + 1
             where challenge.id = :id
               and challenge.verified = false
               and challenge.attempts < challenge.maxAttempts
            """)
    int incrementAttempts(@Param("id") UUID id);
}
