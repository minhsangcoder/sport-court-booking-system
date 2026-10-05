package com.sporthub.identity.repository;

import com.sporthub.identity.domain.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    @EntityGraph(attributePaths = {"roles", "profile"})
    Optional<User> findByEmailIgnoreCase(String email);

    @EntityGraph(attributePaths = {"roles", "profile"})
    Optional<User> findByPhone(String phone);

    @Override
    @EntityGraph(attributePaths = {"roles", "profile"})
    Optional<User> findById(UUID id);

    boolean existsByEmailIgnoreCase(String email);
    boolean existsByPhone(String phone);
    boolean existsByPendingEmailIgnoreCase(String pendingEmail);
    boolean existsByPendingPhone(String pendingPhone);
}
