package com.sporthub.gateway.routing;

import com.sporthub.gateway.config.GatewaySecurityProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

@Component
public class PublicRouteMatcher {

    private final GatewaySecurityProperties properties;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public PublicRouteMatcher(GatewaySecurityProperties properties) {
        this.properties = properties;
    }

    public boolean isPublic(String path) {
        return properties.getPublicPaths().stream()
                .anyMatch(pattern -> pathMatcher.match(pattern, path));
    }
}
