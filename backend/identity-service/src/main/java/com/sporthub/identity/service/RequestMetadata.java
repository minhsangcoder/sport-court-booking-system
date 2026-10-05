package com.sporthub.identity.service;

import jakarta.servlet.http.HttpServletRequest;

public record RequestMetadata(String ipAddress, String userAgent) {
    public static RequestMetadata from(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = forwarded == null || forwarded.isBlank()
                ? request.getRemoteAddr()
                : forwarded.split(",", 2)[0].trim();
        return new RequestMetadata(ip, request.getHeader("User-Agent"));
    }
}
