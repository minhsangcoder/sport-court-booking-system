package com.sporthub.common.security;

/**
 * ThreadLocal holder for the current request's {@link UserContext}.
 *
 * <p>The context is set by {@link GatewayAuthFilter} at the start of each request
 * and cleared in the finally block to prevent memory leaks.</p>
 *
 * <p>Usage in any service layer:</p>
 * <pre>
 * UserContext user = SecurityContextHolder.requireContext();
 * UUID userId = user.getUserId();
 * boolean canAccess = user.hasFacilityPermission(facilityId, "BOOKING_MANAGE");
 * </pre>
 */
public final class SecurityContextHolder {

    private static final ThreadLocal<UserContext> CONTEXT = new ThreadLocal<>();

    private SecurityContextHolder() {
        // utility class
    }

    /**
     * Set the security context for the current thread.
     */
    public static void setContext(UserContext context) {
        CONTEXT.set(context);
    }

    /**
     * Get the security context, may return null for unauthenticated requests.
     */
    public static UserContext getContext() {
        return CONTEXT.get();
    }

    /**
     * Get the security context, throws if not present.
     * Use this in endpoints that require authentication.
     *
     * @throws IllegalStateException if no context is available
     */
    public static UserContext requireContext() {
        UserContext ctx = CONTEXT.get();
        if (ctx == null) {
            throw new IllegalStateException(
                    "No security context available. " +
                    "Ensure the request passed through the API Gateway with valid JWT.");
        }
        return ctx;
    }

    /**
     * Clear the security context. Must be called in a finally block
     * to prevent ThreadLocal memory leaks.
     */
    public static void clear() {
        CONTEXT.remove();
    }
}
