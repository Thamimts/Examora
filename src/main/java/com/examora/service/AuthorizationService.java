package com.examora.service;

import com.examora.exception.ApiException;
import com.examora.model.Role;
import com.examora.model.User;
import com.examora.security.Permission;
import com.examora.security.RolePermissions;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Central authorization facade used by controllers and the security features.
 * It delegates to {@link AuthService#requireUser} for the existing header-based
 * authentication so behavior stays identical to the current app.
 */
@Service
public class AuthorizationService {
    private final AuthService authService;

    public AuthorizationService(AuthService authService) {
        this.authService = authService;
    }

    public User requireUser(String authorizationHeader) {
        return authService.requireUser(authorizationHeader);
    }

    public User requireRole(String authorizationHeader, Role... roles) {
        User user = requireUser(authorizationHeader);
        requireRole(user, roles);
        return user;
    }

    public void requireRole(User user, Role... roles) {
        if (roles != null) {
            for (Role role : roles) {
                if (role == user.role()) {
                    return;
                }
            }
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "You do not have permission to perform this action.");
    }

    public User requireAdmin(String authorizationHeader) {
        return authService.requireAdmin(authorizationHeader);
    }

    public User requirePermission(String authorizationHeader, Permission permission) {
        User user = requireUser(authorizationHeader);
        requirePermission(user, permission);
        return user;
    }

    public void requirePermission(User user, Permission permission) {
        if (!RolePermissions.has(user.role(), permission)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You do not have permission to perform this action.");
        }
    }
}