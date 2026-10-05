package com.sporthub.gateway.security;

import com.sporthub.gateway.config.GatewayJwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import org.springframework.web.reactive.function.client.WebClient;
import com.fasterxml.jackson.databind.JsonNode;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@Component
public class JwtGatewayAuthenticationResolver implements GatewayAuthenticationResolver {
    private final SecretKey key;
    private final WebClient identityClient;

    @org.springframework.beans.factory.annotation.Autowired
    public JwtGatewayAuthenticationResolver(GatewayJwtProperties properties,
            @org.springframework.beans.factory.annotation.Value("${IDENTITY_SERVICE_URL:http://localhost:8081}") String identityUrl) {
        this(properties, WebClient.builder().baseUrl(identityUrl).build());
    }

    public JwtGatewayAuthenticationResolver(GatewayJwtProperties properties, WebClient identityClient) {
        this.identityClient = identityClient;
        byte[] secret = properties.secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("JWT_SECRET must contain at least 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(secret);
    }

    @Override
    public Mono<GatewayIdentity> resolve(ServerWebExchange exchange) {
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return Mono.empty();
        }
        return validateToken(authorization.substring(7)).flatMap(tokenIdentity -> identityClient.get()
                .uri("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, authorization)
                .retrieve().bodyToMono(JsonNode.class)
                .timeout(java.time.Duration.ofSeconds(5))
                .map(body -> {
                    JsonNode user = body.path("data");
                    if (!user.path("id").asText().equals(tokenIdentity.userId().toString())
                            || !user.path("status").asText().equals("ACTIVE"))
                        throw new IllegalArgumentException("Inactive session");
                    java.util.ArrayList<String> roles = new java.util.ArrayList<>();
                    user.path("roles").forEach(role -> roles.add(role.asText()));
                    return new GatewayIdentity(tokenIdentity.userId(), user.path("email").asText(null), roles);
                }));
    }

    public Mono<GatewayIdentity> validateToken(String token) {
        return Mono.fromCallable(() -> {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            Object rawRoles = claims.get("roles");
            List<String> roles = rawRoles instanceof List<?> list
                    ? list.stream().map(String::valueOf).toList()
                    : List.of();
            return new GatewayIdentity(
                    UUID.fromString(claims.getSubject()),
                    claims.get("email", String.class),
                    roles);
        });
    }
}
