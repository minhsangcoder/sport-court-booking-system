package com.sporthub.identity.web;
import com.sporthub.common.dto.ApiResponse;
import com.sporthub.identity.security.AuthenticatedUser;
import com.sporthub.identity.service.ProfileService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
public class AccessController {
    private final ProfileService profiles; private final JdbcTemplate jdbc;
    public AccessController(ProfileService profiles,JdbcTemplate jdbc){this.profiles=profiles;this.jdbc=jdbc;}
    @GetMapping("/api/v1/users/me/access")
    public ApiResponse<AccessView> access(@AuthenticationPrincipal AuthenticatedUser principal){
        var profile=profiles.get(principal.userId());
        var bindings=jdbc.query("SELECT id,facility_id FROM staff_facility_bindings WHERE staff_user_id=? AND is_active=true",
            (rs,n)->new Binding(rs.getObject("facility_id",UUID.class),jdbc.queryForList("SELECT permission FROM staff_permissions WHERE binding_id=?",String.class,rs.getObject("id",UUID.class))),principal.userId());
        bindings.addAll(jdbc.query("SELECT facility_id,state FROM owner_applications WHERE user_id=? AND state<>'APPROVED'",(r,n)->new Binding(r.getObject("facility_id",UUID.class),Set.of("DRAFT","SUPPLEMENT_REQUIRED").contains(r.getString("state"))?List.of("APPLICATION_READ","APPLICATION_EDIT"):List.of("APPLICATION_READ")),principal.userId()));
        return ApiResponse.success(new AccessView(profile.id(),profile.fullName(),profile.roles(),bindings));
    }
    record Binding(UUID facilityId,List<String> permissions){}
    record AccessView(UUID id,String fullName,Set<com.sporthub.identity.domain.Role> roles,List<Binding> facilityBindings){}
}
