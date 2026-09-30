package com.sporthub.common.exception;

/**
 * Thrown when a business rule is violated (HTTP 422 Unprocessable Entity).
 * Examples: Transfer price exceeds original, booking cancellation policy,
 * slot hold expired, group payment deadline passed.
 */
public class BusinessRuleException extends BaseException {

    public BusinessRuleException(String message) {
        super(ErrorCode.BUSINESS_RULE_VIOLATION, message);
    }

    public BusinessRuleException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
