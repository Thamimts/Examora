package com.examora.config;

import org.springframework.util.StringUtils;

public record OAuthProviderConfig(String clientId, String clientSecret, String redirectUri, String authorizeUrl, String tokenUrl, String scopes) {
    public boolean isConfigured() {
        return StringUtils.hasText(clientId) && StringUtils.hasText(clientSecret) && StringUtils.hasText(redirectUri);
    }
}