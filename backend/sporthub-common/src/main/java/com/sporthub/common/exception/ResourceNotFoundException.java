package com.sporthub.common.exception;

import lombok.Getter;

/**
 * Thrown when a requested resource does not exist (HTTP 404).
 */
@Getter
public class ResourceNotFoundException extends BaseException {
    public ResourceNotFoundException(String message) {
        this(ErrorCode.RESOURCE_NOT_FOUND, message);
    }

    private final String resourceType;
    private final String identifier;

    public ResourceNotFoundException(String resourceType, String identifier) {
        super(ErrorCode.RESOURCE_NOT_FOUND,
                String.format("%s not found with identifier: %s", resourceType, identifier));
        this.resourceType = resourceType;
        this.identifier = identifier;
    }

    public ResourceNotFoundException(ErrorCode errorCode, String message) {
        super(errorCode, message);
        this.resourceType = null;
        this.identifier = null;
    }
}
