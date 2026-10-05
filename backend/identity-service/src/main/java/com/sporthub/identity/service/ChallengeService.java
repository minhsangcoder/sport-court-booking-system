package com.sporthub.identity.service;

import com.sporthub.identity.config.IdentityProperties;
import com.sporthub.identity.domain.OtpCode;
import com.sporthub.identity.domain.OtpPurpose;
import com.sporthub.identity.domain.User;
import com.sporthub.identity.exception.IdentityException;
import com.sporthub.identity.repository.OtpCodeRepository;
import com.sporthub.identity.web.dto.VerificationRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ChallengeService {
    private final OtpCodeRepository repository;
    private final ChallengeAttemptService challengeAttemptService;
    private final PasswordEncoder passwordEncoder;
    private final SecretHashingService secrets;
    private final MailDeliveryService mailDelivery;
    private final IdentityProperties properties;

    @Transactional
    public ChallengeIssued issue(User user, OtpPurpose purpose, String recipient) {
        repository.findByUserIdAndPurposeAndVerifiedFalse(user.getId(), purpose)
                .forEach(existing -> {
                    existing.setVerified(true);
                    existing.setVerifiedAt(Instant.now());
                });

        String code = secrets.sixDigitCode();
        String rawToken = secrets.randomToken();
        Instant expiresAt = Instant.now().plus(
                purpose == OtpPurpose.PASSWORD_RESET
                        ? properties.passwordResetExpirationMinutes()
                        : properties.verificationExpirationMinutes(),
                ChronoUnit.MINUTES);

        OtpCode challenge = repository.save(OtpCode.builder()
                .user(user)
                .purpose(purpose)
                .recipient(recipient)
                .codeHash(passwordEncoder.encode(code))
                .tokenHash(secrets.sha256(rawToken))
                .expiresAt(expiresAt)
                .build());
        String verificationToken = challenge.getId() + "." + rawToken;

        if (purpose == OtpPurpose.PASSWORD_RESET) {
            mailDelivery.sendPasswordReset(recipient, challenge.getId(), code, expiresAt);
        } else {
            mailDelivery.sendVerification(recipient, code, verificationToken, expiresAt);
        }
        return new ChallengeIssued(challenge.getId(), expiresAt);
    }

    @Transactional(noRollbackFor = IdentityException.class)
    public OtpCode consume(VerificationRequest request, OtpPurpose expectedPurpose) {
        if (request.verificationToken() != null && !request.verificationToken().isBlank()) {
            String[] parts = request.verificationToken().split("\\.", 2);
            if (parts.length != 2) {
                throw invalidChallenge();
            }
            OtpCode challenge;
            try {
                challenge = load(UUID.fromString(parts[0]), expectedPurpose);
            } catch (IllegalArgumentException exception) {
                throw invalidChallenge();
            }
            validateUsable(challenge);
            if (!secrets.sha256(parts[1]).equals(challenge.getTokenHash())) {
                failAttempt(challenge);
            }
            return markUsed(challenge);
        }
        if (request.challengeId() == null || request.code() == null) {
            throw new IdentityException(HttpStatus.BAD_REQUEST, "IDENTITY-VALIDATION", "A token or challenge code is required");
        }
        OtpCode challenge = load(request.challengeId(), expectedPurpose);
        validateUsable(challenge);
        if (!passwordEncoder.matches(request.code(), challenge.getCodeHash())) {
            failAttempt(challenge);
        }
        return markUsed(challenge);
    }

    @Transactional(noRollbackFor = IdentityException.class)
    public OtpCode consumeReset(UUID challengeId, String code) {
        OtpCode challenge = load(challengeId, OtpPurpose.PASSWORD_RESET);
        validateUsable(challenge);
        if (!passwordEncoder.matches(code, challenge.getCodeHash())) {
            failAttempt(challenge);
        }
        return markUsed(challenge);
    }

    private OtpCode load(UUID id, OtpPurpose purpose) {
        OtpCode challenge = repository.findWithLockById(id).orElseThrow(this::invalidChallenge);
        if (purpose != null && challenge.getPurpose() != purpose) {
            throw invalidChallenge();
        }
        if (purpose == null && challenge.getPurpose() == OtpPurpose.PASSWORD_RESET) {
            throw invalidChallenge();
        }
        return challenge;
    }

    private void validateUsable(OtpCode challenge) {
        if (challenge.isVerified()) {
            throw new IdentityException(HttpStatus.UNPROCESSABLE_ENTITY, "IDENTITY-CHALLENGE-USED", "Challenge has already been used");
        }
        if (!challenge.getExpiresAt().isAfter(Instant.now())) {
            throw new IdentityException(HttpStatus.UNPROCESSABLE_ENTITY, "IDENTITY-CHALLENGE-EXPIRED", "Challenge has expired");
        }
        if (challenge.getAttempts() >= challenge.getMaxAttempts()) {
            throw invalidChallenge();
        }
    }

    private void failAttempt(OtpCode challenge) {
        challenge.setAttempts(challenge.getAttempts() + 1);
        repository.saveAndFlush(challenge);
        throw invalidChallenge();
    }

    private OtpCode markUsed(OtpCode challenge) {
        challenge.setVerified(true);
        challenge.setVerifiedAt(Instant.now());
        return repository.save(challenge);
    }

    private IdentityException invalidChallenge() {
        return new IdentityException(HttpStatus.UNPROCESSABLE_ENTITY, "IDENTITY-CHALLENGE-INVALID", "Challenge is invalid");
    }
}
