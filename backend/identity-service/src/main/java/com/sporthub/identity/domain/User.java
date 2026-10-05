package com.sporthub.identity.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class User {
    @Id @GeneratedValue @UuidGenerator private UUID id;
    @Column(length = 255) private String email;
    @Column(length = 20) private String phone;
    @Column(name = "password_hash", nullable = false, length = 255) private String passwordHash;
    @Column(name = "pending_email", length = 255) private String pendingEmail;
    @Column(name = "pending_phone", length = 20) private String pendingPhone;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private AccountStatus status;
    @Column(name = "email_verified", nullable = false) private boolean emailVerified;
    @Column(name = "phone_verified", nullable = false) private boolean phoneVerified;
    @Column(name = "last_login_at") private Instant lastLoginAt;
    @Column(name = "locked_at") private Instant lockedAt;
    @Column(name = "locked_until") private Instant lockedUntil;
    @Column(name = "lock_reason", length = 500) private String lockReason;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Column(name = "created_by") private UUID createdBy;
    @Column(name = "updated_by") private UUID updatedBy;
    @Version private long version;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Set<Role> roles = new LinkedHashSet<>();

    @OneToOne(mappedBy = "user", fetch = FetchType.LAZY)
    private UserProfile profile;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = createdAt == null ? now : createdAt;
        updatedAt = now;
        status = status == null ? AccountStatus.PENDING_VERIFICATION : status;
    }

    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
