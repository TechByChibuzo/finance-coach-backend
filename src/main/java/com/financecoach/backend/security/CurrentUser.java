package com.financecoach.backend.security;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

/**
 * The logged-in user's ID, which JwtAuthenticationFilter sets as the principal.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /**
     * @throws AuthenticationCredentialsNotFoundException (401) when no user is logged in
     */
    public static UUID id() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof UUID userId)) {
            throw new AuthenticationCredentialsNotFoundException("User not authenticated");
        }
        return userId;
    }
}
