package com.examora.security;

import com.examora.config.OAuthConfig;
import com.examora.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component("github")
public class GithubOAuthClient extends AbstractOAuthClient {
    private static final String USERINFO_URL = "https://api.github.com/user";
    private static final String EMAILS_URL = "https://api.github.com/user/emails";
    private static final String GITHUB_ACCEPT = "application/vnd.github+json";

    public GithubOAuthClient(OAuthConfig config, ObjectMapper objectMapper) {
        super(config.config(OAuthProvider.GITHUB), objectMapper);
    }

    @Override
    public OAuthProvider provider() {
        return OAuthProvider.GITHUB;
    }

    @Override
    public String buildAuthorizeUrl(String state) {
        return config.authorizeUrl()
                + "?response_type=code"
                + "&client_id=" + urlEncode(config.clientId())
                + "&redirect_uri=" + urlEncode(config.redirectUri())
                + "&scope=" + urlEncode(config.scopes())
                + "&state=" + urlEncode(state);
    }

    @Override
    public OAuthUserInfo exchangeCode(String code, String redirectUri) {
        String accessToken = acquireAccessToken(code);
        JsonNode user = getJson(USERINFO_URL, accessToken, GITHUB_ACCEPT);
        String providerUserId = user.path("id").asText(null);
        if (providerUserId == null || providerUserId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The sign-in service returned no profile identifier.");
        }
        String name = user.path("name").asText(null);
        if (name == null || name.isBlank()) {
            name = user.path("login").asText(null);
        }
        String email = user.path("email").asText(null);
        if (email == null || email.isBlank()) {
            email = findPrimaryEmail(accessToken);
        }
        return new OAuthUserInfo(OAuthProvider.GITHUB, providerUserId, email, name);
    }

    private String findPrimaryEmail(String accessToken) {
        JsonNode emails = getJson(EMAILS_URL, accessToken, GITHUB_ACCEPT);
        if (emails.isArray()) {
            for (JsonNode entry : emails) {
                boolean primary = entry.path("primary").asBoolean(false);
                boolean verified = entry.path("verified").asBoolean(false);
                String email = entry.path("email").asText(null);
                if (primary && verified && email != null && !email.isBlank()) {
                    return email;
                }
            }
        }
        return null;
    }
}