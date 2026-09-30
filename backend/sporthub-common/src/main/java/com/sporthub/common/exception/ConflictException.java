package com.sporthub.common.exception;

/**
 * Thrown when an operation conflicts with existing state (HTTP 409).
 * Examples: duplicate email, slot already held, optimistic lock failure.
 */
public class ConflictException extends BaseException {

    public ConflictException(String message) {
        super(ErrorCode.CONFLICT, message);
    }

    public ConflictException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
