package com.examora.security;

import com.examora.config.OAuthConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component("google")
public class GoogleOAuthClient extends AbstractOAuthClient {
    private static final String USERINFO_URL = "https://openidconnect.googleapis.com/v1/userinfo";

    public GoogleOAuthClient(OAuthConfig config, ObjectMapper objectMapper) {
        super(config.config(OAuthProvider.GOOGLE), objectMapper);
    }

    @Override
    public OAuthProvider provider() {
        return OAuthProvider.GOOGLE;
    }

    @Override
    public String buildAuthorizeUrl(String state) {
        return config.authorizeUrl()
                + "?response_type=code"
                + "&client_id=" + urlEncode(config.clientId())
                + "&redirect_uri=" + urlEncode(config.redirectUri())
                + "&scope=" + urlEncode(config.scopes())
                + "&state=" + urlEncode(state)
                + "&prompt=select_account";
    }

    @Override
    public OAuthUserInfo exchangeCode(String code, String redirectUri) {
        String accessToken = acquireAccessToken(code);
        return parseUserInfo(getJson(USERINFO_URL, accessToken, "application/json"));
    }

    static OAuthUserInfo parseUserInfo(com.fasterxml.jackson.databind.JsonNode user) {
        String providerUserId = user.path("sub").asText(null);
        String email = user.path("email").asText(null);
        boolean emailVerified = user.path("email_verified").asBoolean(false);
        if (providerUserId == null || providerUserId.isBlank()) {
            throw new com.examora.exception.ApiException(
                    org.springframework.http.HttpStatus.BAD_GATEWAY, "The sign-in service returned no profile identifier.");
        }
        String name = user.path("name").asText(null);
        if (name == null || name.isBlank()) {
            name = user.path("given_name").asText(null);
        }
        return new OAuthUserInfo(OAuthProvider.GOOGLE, providerUserId, email, name, emailVerified);
    }
}