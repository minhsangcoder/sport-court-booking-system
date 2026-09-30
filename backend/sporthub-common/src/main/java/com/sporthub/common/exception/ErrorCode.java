package com.sporthub.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Technical error codes shared by all SportHub microservices.
 * Domain-specific codes belong to their owning service.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // ── Common ─────────────────────────────────────────────────────────
    RESOURCE_NOT_FOUND("COMMON-001", "Resource not found"),
    CONFLICT("COMMON-002", "Resource conflict"),
    UNAUTHORIZED("COMMON-003", "Unauthorized access"),
    FORBIDDEN("COMMON-004", "Access forbidden"),
    VALIDATION_ERROR("COMMON-005", "Validation failed"),
    INTERNAL_ERROR("COMMON-006", "Internal server error"),
    BUSINESS_RULE_VIOLATION("COMMON-007", "Business rule violation"),
    OPTIMISTIC_LOCK_CONFLICT("COMMON-008", "Resource was modified by another user");

    private final String code;
    private final String defaultMessage;
}
