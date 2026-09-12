package com.examora.config;

import com.examora.model.Role;
import com.examora.security.OAuthProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Environment-driven OAuth settings. OAuth stays fully disabled until
 * {@code OAUTH_ENABLED=true}; providers are enabled individually once their
 * client credentials and redirect URI are configured.
 */
@Component
public class OAuthConfig {
    private final boolean enabled;
    private final Role defaultRole;
    private final String frontendBase;
    private final int stateTtlSeconds;
    private final OAuthProviderConfig google;
    private final OAuthProviderConfig github;

    public OAuthConfig(@Value("${examora.oauth.enabled:false}") boolean enabled,
                       @Value("${examora.oauth.default-role:STUDENT}") String defaultRole,
                       @Value("${examora.oauth.frontend-base:http://localhost:3000}") String frontendBase,
                       @Value("${examora.oauth.state-ttl-seconds:600}") int stateTtlSeconds,
                       @Value("${examora.oauth.google.client-id:}") String googleClientId,
                       @Value("${examora.oauth.google.client-secret:}") String googleClientSecret,
                       @Value("${examora.oauth.google.redirect-uri:}") String googleRedirectUri,
                       @Value("${examora.oauth.github.client-id:}") String githubClientId,
                       @Value("${examora.oauth.github.client-secret:}") String githubClientSecret,
                       @Value("${examora.oauth.github.redirect-uri:}") String githubRedirectUri) {
        this.enabled = enabled;
        this.defaultRole = parseRole(defaultRole);
        this.frontendBase = stripTrailingSlash(frontendBase);
        this.stateTtlSeconds = Math.max(60, stateTtlSeconds);
        this.google = new OAuthProviderConfig(
                googleClientId, googleClientSecret, googleRedirectUri,
                "https://accounts.google.com/o/oauth2/v2/auth",
                "https://oauth2.googleapis.com/token",
                "openid email profile");
        this.github = new OAuthProviderConfig(
                githubClientId, githubClientSecret, githubRedirectUri,
                "https://github.com/login/oauth/authorize",
                "https://github.com/login/oauth/access_token",
                "read:user user:email");
    }

    public boolean enabled() {
        return enabled;
    }

    public Role defaultRole() {
        return defaultRole;
    }

    public String frontendBase() {
        return frontendBase;
    }

    public int stateTtlSeconds() {
        return stateTtlSeconds;
    }

    public boolean isConfigured(OAuthProvider provider) {
        return enabled && config(provider).isConfigured();
    }

    public OAuthProviderConfig config(OAuthProvider provider) {
        return switch (provider) {
            case GOOGLE -> google;
            case GITHUB -> github;
        };
    }

    private Role parseRole(String value) {
        try {
            return Role.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            return Role.STUDENT;
        }
    }

    private String stripTrailingSlash(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }
}