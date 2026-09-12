package com.examora.security;

import com.examora.exception.ApiException;
import com.examora.service.OAuthService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleOAuthClientParseTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private OAuthUserInfo parse(String json) throws Exception {
        return GoogleOAuthClient.parseUserInfo(mapper.readTree(json));
    }

    @Test
    void verifiedUserInfoCarriesTheVerifiedFlag() throws Exception {
        OAuthUserInfo info = parse(
                "{\"sub\":\"google-sub-1\",\"email\":\"verified@example.com\",\"email_verified\":true,\"name\":\"Verified User\"}");

        assertThat(info.provider()).isEqualTo(OAuthProvider.GOOGLE);
        assertThat(info.providerUserId()).isEqualTo("google-sub-1");
        assertThat(info.email()).isEqualTo("verified@example.com");
        assertThat(info.emailVerified()).isTrue();
        assertThat(info.name()).isEqualTo("Verified User");
    }

    @Test
    void explicitlyFalseEmailVerifiedIsRejectedByTheGate() throws Exception {
        OAuthUserInfo info = parse(
                "{\"sub\":\"google-sub-2\",\"email\":\"unverified@example.com\",\"email_verified\":false}");

        assertThat(info.emailVerified()).isFalse();
        assertThatThrownBy(() -> OAuthService.verifyEmailForSignIn(info))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void missingEmailVerifiedDefaultsToFailClosed() throws Exception {
        OAuthUserInfo info = parse(
                "{\"sub\":\"google-sub-3\",\"email\":\"no-claim@example.com\",\"name\":\"No Claim\"}");

        assertThat(info.emailVerified()).isFalse();
        assertThatThrownBy(() -> OAuthService.verifyEmailForSignIn(info))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void missingSubThrowsBadGateway() throws Exception {
        assertThatThrownBy(() -> parse(
                "{\"email\":\"no-profile@example.com\",\"email_verified\":true}"))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.status()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    void nameFallsBackToGivenNameWhenUnset() throws Exception {
        OAuthUserInfo info = parse(
                "{\"sub\":\"google-sub-4\",\"email\":\"given@example.com\",\"given_name\":\"Given Name\"}");

        assertThat(info.name()).isEqualTo("Given Name");
    }
}