package com.sporthub.common.exception;

/**
 * Thrown when the authenticated user lacks the required role/permission (HTTP 403).
 * Example: Staff trying to access a facility they're not assigned to.
 */
public class ForbiddenException extends BaseException {

    public ForbiddenException(String message) {
        super(ErrorCode.FORBIDDEN, message);
    }

    public ForbiddenException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
