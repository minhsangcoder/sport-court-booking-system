package com.sporthub.identity.service;

import com.sporthub.identity.repository.OtpCodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ChallengeAttemptService {
    private final OtpCodeRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(UUID challengeId) {
        repository.incrementAttempts(challengeId);
    }
}
