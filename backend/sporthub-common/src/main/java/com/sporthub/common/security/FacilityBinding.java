package com.sporthub.common.security;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Represents a Staff member's binding to a specific Facility.
 * Embedded in JWT claims as {@code facilityBindings[]}.
 *
 * <p>Per ADR-004, Staff roles are strictly facility-scoped:
 * a Staff member can only operate within their assigned facilities,
 * and each binding carries a specific set of permissions.</p>
 *
 * <p>Example JWT claim:</p>
 * <pre>
 * "facilityBindings": [
 *   { "facilityId": "uuid-1", "permissions": ["BOOKING_MANAGE", "CHECKIN"] },
 *   { "facilityId": "uuid-2", "permissions": ["BOOKING_VIEW"] }
 * ]
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FacilityBinding {

    private UUID facilityId;
    private List<String> permissions;

    public boolean hasPermission(String permission) {
        return permissions != null && permissions.contains(permission);
    }
}
