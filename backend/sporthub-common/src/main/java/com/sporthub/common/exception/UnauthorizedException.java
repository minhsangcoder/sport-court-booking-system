package com.sporthub.common.exception;

/**
 * Thrown when authentication fails or token is invalid/expired (HTTP 401).
 */
public class UnauthorizedException extends BaseException {

    public UnauthorizedException(String message) {
        super(ErrorCode.UNAUTHORIZED, message);
    }

    public UnauthorizedException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
