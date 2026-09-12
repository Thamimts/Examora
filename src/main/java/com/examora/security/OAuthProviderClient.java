package com.examora.security;

/**
 * Strategy for a single OAuth provider. Implementations are Spring beans named
 * after the provider (for example {@code google}, {@code github}) and are looked
 * up by {@link com.examora.service.OAuthService}.
 */
public interface OAuthProviderClient {
    OAuthProvider provider();

    String buildAuthorizeUrl(String state);

    OAuthUserInfo exchangeCode(String code, String redirectUri);
}