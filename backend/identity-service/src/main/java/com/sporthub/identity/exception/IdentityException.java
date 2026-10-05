package com.sporthub.identity.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class IdentityException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public IdentityException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
