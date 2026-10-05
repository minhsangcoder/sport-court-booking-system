package com.sporthub.common.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.sporthub.common.exception.UnauthorizedException;
import com.sporthub.common.exception.ForbiddenException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.util.*;

/** Technical caller context fetched from Identity. Never trusts client identity headers. */
@Component
public class RemoteIdentity {
    private final RestClient client;
    public RemoteIdentity(@Value("${IDENTITY_SERVICE_URL:http://localhost:8081}") String url) {
        var factory=new org.springframework.http.client.JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build());
        factory.setReadTimeout(java.time.Duration.ofSeconds(5));
        client=RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }
    public Caller current(HttpServletRequest request) {
        if(request.getAttribute("sporthub.caller") instanceof Caller caller) return caller;
        String token=request.getHeader("Authorization");
        if(token==null || !token.startsWith("Bearer ")) throw new UnauthorizedException("Authentication required");
        JsonNode user;
        try { user=client.get().uri("/api/v1/users/me/access").header("Authorization",token).retrieve().body(JsonNode.class).path("data"); }
        catch(org.springframework.web.client.HttpClientErrorException ex) { throw new UnauthorizedException("Session is invalid or expired"); }
        var roles=new HashSet<String>(); user.path("roles").forEach(role->roles.add(role.asText()));
        var bindings=new HashMap<UUID,Set<String>>();
        user.path("facilityBindings").forEach(binding->{
            var permissions=new HashSet<String>(); binding.path("permissions").forEach(p->permissions.add(p.asText()));
            bindings.put(UUID.fromString(binding.path("facilityId").asText()), Set.copyOf(permissions));
        });
        Caller caller=new Caller(UUID.fromString(user.path("id").asText()),user.path("fullName").asText(),Set.copyOf(roles),Map.copyOf(bindings));
        request.setAttribute("sporthub.caller",caller); return caller;
    }
    public record Caller(UUID id,String fullName,Set<String> roles,Map<UUID,Set<String>> facilityBindings) {
        public boolean hasRole(String role) {return roles.contains(role);}
        public void requireRole(String role) {if(!hasRole(role)) throw new ForbiddenException("Required role: "+role);}
    }
}
