package com.sporthub.identity.security;

import com.sporthub.identity.config.JwtProperties;
import com.sporthub.identity.domain.Role;
import com.sporthub.identity.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class JwtService {
    private final JwtProperties properties;
    private final SecretKey key;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        byte[] secret = properties.secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("JWT_SECRET must contain at least 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(secret);
    }

    public String createAccessToken(User user, UUID sessionId) {
        Instant now = Instant.now();
        List<String> roles = user.getRoles().stream().map(Enum::name).sorted().toList();
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("roles", roles)
                .claim("sid", sessionId.toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(properties.accessTokenExpiration())))
                .signWith(key)
                .compact();
    }

    public AuthenticatedUser parseAccessToken(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
        Set<Role> roles = new LinkedHashSet<>();
        Object rawRoles = claims.get("roles");
        if (rawRoles instanceof List<?> values) {
            values.stream().map(String::valueOf).map(Role::valueOf).forEach(roles::add);
        }
        return new AuthenticatedUser(
                UUID.fromString(claims.getSubject()),
                claims.get("email", String.class),
                roles,
                UUID.fromString(claims.get("sid", String.class)));
    }

    public int accessTokenLifetimeSeconds() {
        return Math.toIntExact(properties.accessTokenExpiration() / 1000);
    }

    public long refreshTokenLifetimeMillis() {
        return properties.refreshTokenExpiration();
    }
}
