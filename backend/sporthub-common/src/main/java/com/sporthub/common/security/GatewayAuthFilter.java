package com.sporthub.common.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Servlet filter that extracts user identity from Gateway-forwarded headers
 * and populates the {@link SecurityContextHolder} for the current request.
 *
 * <p>The API Gateway validates the JWT token and forwards the following headers
 * to downstream services:</p>
 * <ul>
 *   <li>{@code X-User-Id} — UUID of the authenticated user</li>
 *   <li>{@code X-User-Email} — Email address</li>
 *   <li>{@code X-User-Role} — Role (CUSTOMER, OWNER, STAFF, ADMIN)</li>
 *   <li>{@code X-Facility-Bindings} — JSON array of facility bindings (Staff only)</li>
 * </ul>
 *
 * <p>For unauthenticated/public endpoints, no headers are present and no context is set.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class GatewayAuthFilter implements Filter {

    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_USER_EMAIL = "X-User-Email";
    public static final String HEADER_USER_ROLE = "X-User-Role";
    public static final String HEADER_FACILITY_BINDINGS = "X-Facility-Bindings";

    private final ObjectMapper objectMapper;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;

        try {
            String userId = httpRequest.getHeader(HEADER_USER_ID);

            if (StringUtils.hasText(userId)) {
                UserContext context = UserContext.builder()
                        .userId(UUID.fromString(userId))
                        .email(httpRequest.getHeader(HEADER_USER_EMAIL))
                        .role(httpRequest.getHeader(HEADER_USER_ROLE))
                        .facilityBindings(parseFacilityBindings(
                                httpRequest.getHeader(HEADER_FACILITY_BINDINGS)))
                        .build();

                SecurityContextHolder.setContext(context);

                log.debug("Security context set — userId: {}, role: {}, bindings: {}",
                        userId, context.getRole(),
                        context.getFacilityBindings().size());
            }

            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clear();
        }
    }

    private List<FacilityBinding> parseFacilityBindings(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<FacilityBinding>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse X-Facility-Bindings header: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
