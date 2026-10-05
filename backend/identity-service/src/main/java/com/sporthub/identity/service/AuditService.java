package com.sporthub.identity.service;

import com.sporthub.identity.domain.AuditLog;
import com.sporthub.identity.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuditService {
    private final AuditLogRepository repository;

    public void record(UUID userId, String action, String entityType, UUID entityId, RequestMetadata metadata) {
        repository.save(AuditLog.builder()
                .userId(userId)
                .action(action)
                .entityType(entityType)
                .entityId(entityId)
                .ipAddress(metadata.ipAddress())
                .userAgent(metadata.userAgent())
                .build());
    }
}
