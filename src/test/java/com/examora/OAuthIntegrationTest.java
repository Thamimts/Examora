package com.examora;

import com.examora.dto.AuthDtos.AuthResponse;
import com.examora.exception.ApiException;
import com.examora.security.OAuthProvider;
import com.examora.security.OAuthStateCodec;
import com.examora.service.OAuthService;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:examora;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "examora.jwt.secret=test-secret-for-examora-oauth-tests-change-in-real-use",
        "examora.jwt.expiration-hours=24",
        "examora.oauth.enabled=true",
        "examora.oauth.default-role=STUDENT",
        "examora.oauth.frontend-base=http://localhost:3000",
        "examora.oauth.google.client-id=test-google-id",
        "examora.oauth.google.client-secret=test-google-secret",
        "examora.oauth.google.redirect-uri=http://localhost:8080/api/auth/oauth/google/callback"
})
class OAuthIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OAuthService oauthService;

    @Autowired
    private OAuthStateCodec stateCodec;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from oauth_accounts");
        jdbcTemplate.update("delete from two_factor_challenges");
        jdbcTemplate.update("delete from two_factor_recovery_codes");
        jdbcTemplate.update("delete from activity_events");
        jdbcTemplate.update("delete from users");
    }

    @Test
    void providersEndpointListsConfiguredVisibility() throws Exception {
        mockMvc.perform(get("/api/auth/oauth/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void unknownProviderStartIsNotFound() throws Exception {
        mockMvc.perform(get("/api/auth/oauth/not-a-provider/start"))
                .andExpect(status().isNotFound());
    }

    @Test
    void unconfiguredProviderStartIsNotImplemented() throws Exception {
        mockMvc.perform(get("/api/auth/oauth/github/start"))
                .andExpect(status().isNotImplemented());
    }

    @Test
    void configuredProviderStartRedirectsAndStateRoundTripsReturnTo() throws Exception {
        String location = mockMvc.perform(get("/api/auth/oauth/google/start")
                        .param("returnTo", "/settings/security"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("accounts.google.com")))
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("client_id=test-google-id")))
                .andReturn()
                .getResponse()
                .getHeader("Location");

        String state = extractState(location);
        OAuthStateCodec.State decoded = stateCodec.verify(state, OAuthProvider.GOOGLE);
        assertThat(decoded.returnTo()).isEqualTo("/settings/security");
    }

    @Test
    void callbackWithInvalidStateRedirectsToLoginError() throws Exception {
        mockMvc.perform(get("/api/auth/oauth/google/callback")
                        .param("code", "bogus-code")
                        .param("state", "tampered-state"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("http://localhost:3000/login?error=")));
    }

    @Test
    void finalizeOauthCreatesStudentAccountAndPersistsIdentity() {
        AuthResponse response = oauthService.finalizeOAuth(OAuthProvider.GOOGLE, "google-profile-1", "new-oauth@example.com", "New OAuth User");

        assertThat(response.user().role().name()).isEqualTo("STUDENT");
        assertThat(response.token()).isNotBlank();
        assertThat(jdbcTemplate.queryForObject("select count(*) from oauth_accounts where provider = 'GOOGLE' and provider_user_id = 'google-profile-1'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from users where email = 'new-oauth@example.com' and password_hash is null", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void finalizeOauthReSignInReturnsTheSameAccount() {
        AuthResponse first = oauthService.finalizeOAuth(OAuthProvider.GOOGLE, "google-profile-2", "re-signin@example.com", "Re Sign In");
        AuthResponse second = oauthService.finalizeOAuth(OAuthProvider.GOOGLE, "google-profile-2", "re-signin@example.com", "Re Sign In");

        assertThat(second.user().id()).isEqualTo(first.user().id());
        assertThat(jdbcTemplate.queryForObject("select count(*) from oauth_accounts where provider_user_id = 'google-profile-2'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from users where email = 're-signin@example.com'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void finalizeOauthLinksToAnExistingPasswordAccountByEmail() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Password Account\",\"email\":\"link-existing@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isOk());

        String existingId = jdbcTemplate.queryForObject("select id from users where email = 'link-existing@example.com'", String.class);
        AuthResponse response = oauthService.finalizeOAuth(OAuthProvider.GITHUB, "github-profile-1", "link-existing@example.com", "Linked Account");

        assertThat(response.user().id()).isEqualTo(existingId);
        assertThat(jdbcTemplate.queryForObject("select count(*) from oauth_accounts where provider = 'GITHUB' and provider_user_id = 'github-profile-1' and user_id = ?", Integer.class, existingId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from users where email = 'link-existing@example.com'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void finalizeOauthRejectsASecondIdentityForTheSamePrimaryAccount() {
        oauthService.finalizeOAuth(OAuthProvider.GITHUB, "github-primary", "primary@example.com", "Primary Account");

        assertThatThrownBy(() -> oauthService.finalizeOAuth(OAuthProvider.GITHUB, "github-other", "primary@example.com", "Other Identity"))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT));
    }

    private String extractState(String location) {
        int start = location.indexOf("state=") + "state=".length();
        int end = location.indexOf('&', start);
        String encoded = end < 0 ? location.substring(start) : location.substring(start, end);
        return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
    }
}