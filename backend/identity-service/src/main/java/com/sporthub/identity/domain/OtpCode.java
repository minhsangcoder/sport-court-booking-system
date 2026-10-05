package com.sporthub.identity.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "otp_codes")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class OtpCode {
    @Id @GeneratedValue @UuidGenerator private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false) private User user;
    @Column(name = "code_hash", nullable = false, length = 255) private String codeHash;
    @Column(name = "token_hash", length = 64) private String tokenHash;
    @Column(length = 255) private String recipient;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private OtpPurpose purpose;
    @Column(nullable = false) private int attempts;
    @Column(name = "max_attempts", nullable = false) @Builder.Default private int maxAttempts = 5;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(nullable = false) private boolean verified;
    @Column(name = "verified_at") private Instant verifiedAt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    @PrePersist void onCreate() { createdAt = createdAt == null ? Instant.now() : createdAt; }
    public boolean isUsableAt(Instant now) { return !verified && attempts < maxAttempts && expiresAt.isAfter(now); }
}
