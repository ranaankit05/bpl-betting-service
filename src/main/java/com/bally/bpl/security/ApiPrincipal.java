package com.bally.bpl.security;

/**
 * The authenticated caller. For {@link Role#USER} the {@code userId} is bound to the API key, so a user
 * can never act on behalf of somebody else; it is {@code null} for admins.
 */
public record ApiPrincipal(String name, Role role, String userId) {

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
