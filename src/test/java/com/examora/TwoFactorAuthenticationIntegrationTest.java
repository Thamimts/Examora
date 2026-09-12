package com.examora;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.examora.security.Base32;
import com.examora.security.Totp;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
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
        "examora.jwt.secret=test-secret-for-examora-two-factor-tests-change-in-real-use",
        "examora.jwt.expiration-hours=24"
})
class TwoFactorAuthenticationIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from two_factor_challenges");
        jdbcTemplate.update("delete from two_factor_recovery_codes");
        jdbcTemplate.update("delete from oauth_accounts");
        jdbcTemplate.update("delete from activity_events");
        jdbcTemplate.update("delete from users");
    }

    @Test
    void enablingTwoFactorReturnsRecoveryCodesAndFlipsStatus() throws Exception {
        String token = register("2fa-enable@example.com", "password123");

        mockMvc.perform(get("/api/auth/2fa/status").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false));

        String secret = twoFactorSetup(token);

        mockMvc.perform(post("/api/auth/2fa/confirm")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + totp(secret) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recoveryCodes.length()").value(10));

        mockMvc.perform(get("/api/auth/2fa/status").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true));

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from two_factor_recovery_codes", Integer.class)).isEqualTo(10);
    }

    @Test
    void loginWithTwoFactorEnabledReturnsChallengeInsteadOfToken() throws Exception {
        String token = register("2fa-challenge@example.com", "password123");
        String secret = twoFactorSetup(token);
        confirmTwoFactor(token, secret);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"2fa-challenge@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requiresTwoFactor").value(true))
                .andExpect(jsonPath("$.data.token").doesNotExist())
                .andExpect(jsonPath("$.data.user").doesNotExist())
                .andExpect(jsonPath("$.data.challengeToken").isString());
    }

    @Test
    void validTotpCodeCompletesLoginAndChallengeIsSingleUse() throws Exception {
        String token = register("2fa-verify@example.com", "password123");
        String secret = twoFactorSetup(token);
        confirmTwoFactor(token, secret);

        String challenge = loginChallenge("2fa-verify@example.com", "password123");

        mockMvc.perform(post("/api/auth/2fa/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeToken\":\"" + challenge + "\",\"code\":\"" + totp(secret) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isString())
                .andExpect(jsonPath("$.data.user.email").value("2fa-verify@example.com"))
                .andExpect(jsonPath("$.data.user.role").value("STUDENT"));

        mockMvc.perform(post("/api/auth/2fa/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeToken\":\"" + challenge + "\",\"code\":\"" + totp(secret) + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongTotpCodeIsRejected() throws Exception {
        String token = register("2fa-wrong@example.com", "password123");
        String secret = twoFactorSetup(token);
        confirmTwoFactor(token, secret);
        String challenge = loginChallenge("2fa-wrong@example.com", "password123");

        mockMvc.perform(post("/api/auth/2fa/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeToken\":\"" + challenge + "\",\"code\":\"123456\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void recoveryCodeCompletesLoginAndIsConsumedOnFirstUse() throws Exception {
        String token = register("2fa-recover@example.com", "password123");
        String secret = twoFactorSetup(token);
        String recoveryCode = confirmTwoFactor(token, secret);

        String challenge = loginChallenge("2fa-recover@example.com", "password123");
        mockMvc.perform(post("/api/auth/2fa/recover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeToken\":\"" + challenge + "\",\"recoveryCode\":\"" + recoveryCode + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isString())
                .andExpect(jsonPath("$.data.user.email").value("2fa-recover@example.com"));

        String secondChallenge = loginChallenge("2fa-recover@example.com", "password123");
        mockMvc.perform(post("/api/auth/2fa/recover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeToken\":\"" + secondChallenge + "\",\"recoveryCode\":\"" + recoveryCode + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void repeatedWrongOneTimeCodesAreRateLimited() throws Exception {
        String token = register("2fa-ratelimit@example.com", "password123");
        String secret = twoFactorSetup(token);
        confirmTwoFactor(token, secret);
        String email = "2fa-ratelimit@example.com";

        for (int attempt = 0; attempt < 5; attempt++) {
            String challenge = loginChallenge(email, "password123");
            mockMvc.perform(post("/api/auth/2fa/verify")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"challengeToken\":\"" + challenge + "\",\"code\":\"123456\"}"))
                    .andExpect(status().isUnauthorized());
        }

        String blockedChallenge = loginChallenge(email, "password123");
        mockMvc.perform(post("/api/auth/2fa/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeToken\":\"" + blockedChallenge + "\",\"code\":\"123456\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void changePasswordRejectsWrongCurrentPasswordAndRestoresLoginWithNewOne() throws Exception {
        String token = register("2fa-password@example.com", "password123");

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"wrong-password\",\"newPassword\":\"newpassword123\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"password123\",\"newPassword\":\"newpassword123\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"2fa-password@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"2fa-password@example.com\",\"password\":\"newpassword123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isString());
    }

    @Test
    void disableWithPasswordDisablesTwoFactorAndLoginReturnsNormalResult() throws Exception {
        String token = register("2fa-disable@example.com", "password123");
        String secret = twoFactorSetup(token);
        confirmTwoFactor(token, secret);

        mockMvc.perform(post("/api/auth/2fa/disable")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"password123\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/auth/2fa/status").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"2fa-disable@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requiresTwoFactor").value(false))
                .andExpect(jsonPath("$.data.token").isString());
    }

    private String register(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Test User\",\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isString())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("token").asText();
    }

    private String twoFactorSetup(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/2fa/setup")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.secret").isString())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("secret").asText();
    }

    private String confirmTwoFactor(String token, String secret) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/2fa/confirm")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + totp(secret) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recoveryCodes[0]").isString())
                .andReturn();
        JsonNode codes = objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("recoveryCodes");
        return codes.get(0).asText();
    }

    private String loginChallenge(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requiresTwoFactor").value(true))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("challengeToken").asText();
    }

    private String totp(String secret) {
        return Totp.generate(Base32.decode(secret), Instant.now(), 30, 6);
    }
}