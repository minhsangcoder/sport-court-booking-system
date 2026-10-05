package com.sporthub.identity.service;

import java.time.Instant;
import java.util.UUID;

public record ChallengeIssued(UUID id, Instant expiresAt) {
}
