package com.sporthub.common.security;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Holds the authenticated user's context for the current request.
 * Populated by {@link GatewayAuthFilter} from Gateway-forwarded headers.
 *
 * <p>Available roles: CUSTOMER, OWNER, STAFF, ADMIN</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserContext {

    private UUID userId;
    private String email;
    private String role; // CUSTOMER, OWNER, STAFF, ADMIN

    @Builder.Default
    private List<FacilityBinding> facilityBindings = Collections.emptyList();

    // ── Role checks ─────────────────────────────────────────────────

    public boolean hasRole(String requiredRole) {
        return role != null && role.equalsIgnoreCase(requiredRole);
    }

    public boolean isAdmin() {
        return hasRole("ADMIN");
    }

    public boolean isOwner() {
        return hasRole("OWNER");
    }

    public boolean isStaff() {
        return hasRole("STAFF");
    }

    public boolean isCustomer() {
        return hasRole("CUSTOMER");
    }

    // ── Facility-scoped authorization ───────────────────────────────

    /**
     * Check if this user has access to a specific facility.
     * Admin has unrestricted access; Owner checks are done at service level.
     */
    public boolean hasFacilityAccess(UUID facilityId) {
        if (isAdmin()) return true;
        if (facilityBindings == null || facilityBindings.isEmpty()) return false;
        return facilityBindings.stream()
                .anyMatch(binding -> binding.getFacilityId().equals(facilityId));
    }

    /**
     * Check if this user has a specific permission at a specific facility.
     * Admin bypasses all permission checks.
     */
    public boolean hasFacilityPermission(UUID facilityId, String permission) {
        if (isAdmin()) return true;
        if (facilityBindings == null) return false;
        return facilityBindings.stream()
                .filter(binding -> binding.getFacilityId().equals(facilityId))
                .anyMatch(binding -> binding.hasPermission(permission));
    }
}
