package com.examora.service;

import com.examora.dto.AuthDtos.RecoveryCodesResponse;
import com.examora.dto.AuthDtos.TwoFactorSetupResponse;
import com.examora.exception.ApiException;
import com.examora.exception.TooManyRequestsException;
import com.examora.model.User;
import com.examora.repository.TwoFactorRepository;
import com.examora.repository.UserRepository;
import com.examora.security.Base32;
import com.examora.security.Totp;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class TwoFactorService {
    private static final String RECOVERY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TwoFactorRepository repository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final OtpRateLimiter otpRateLimiter;
    private final ActivityService activityService;
    private final String issuer;
    private final int digits;
    private final int periodSeconds;
    private final int window;
    private final int challengeTtlSeconds;
    private final int recoveryCodeCount;

    public TwoFactorService(TwoFactorRepository repository,
                            UserRepository userRepository,
                            PasswordEncoder passwordEncoder,
                            OtpRateLimiter otpRateLimiter,
                            ActivityService activityService,
                            @Value("${examora.two-factor.issuer:Examora}") String issuer,
                            @Value("${examora.two-factor.digits:6}") int digits,
                            @Value("${examora.two-factor.period-seconds:30}") int periodSeconds,
                            @Value("${examora.two-factor.window:1}") int window,
                            @Value("${examora.two-factor.challenge-ttl-seconds:300}") int challengeTtlSeconds,
                            @Value("${examora.two-factor.recovery-code-count:10}") int recoveryCodeCount) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.otpRateLimiter = otpRateLimiter;
        this.activityService = activityService;
        this.issuer = issuer;
        this.digits = Math.max(6, digits);
        this.periodSeconds = Math.max(1, periodSeconds);
        this.window = Math.max(0, window);
        this.challengeTtlSeconds = Math.max(60, challengeTtlSeconds);
        this.recoveryCodeCount = Math.min(Math.max(1, recoveryCodeCount), 20);
    }

    public boolean isEnabled(String userId) {
        return repository.hasEnabledTwoFactor(userId);
    }

    public String issueChallenge(String userId) {
        repository.deleteExpiredChallenges(userId, Instant.now());
        String token = randomHex(32);
        repository.createChallenge(
                java.util.UUID.randomUUID().toString(),
                userId,
                TwoFactorRepository.sha256Hex(token),
                "AUTH",
                Instant.now().plusSeconds(challengeTtlSeconds));
        return token;
    }

    public User completeTwoFactor(String challengeToken, String code, String ip) {
        String userId = consumeChallengeOrThrow(challengeToken);
        rateLimit("otp:" + userId, ip);
        byte[] secret = repository.findTotpSecret(userId)
                .map(this::decodeSecret)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Two-factor authentication is not enabled for this account."));
        if (Totp.verify(secret, code, Instant.now(), periodSeconds, digits, window).isEmpty()) {
            otpRateLimiter.recordFailure("otp:" + userId, ip);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid verification code.");
        }
        return requireUser(userId);
    }

    public User completeRecovery(String challengeToken, String recoveryCode, String ip) {
        String userId = consumeChallengeOrThrow(challengeToken);
        rateLimit("recovery:" + userId, ip);
        String normalized = normalizeRecoveryCode(recoveryCode);
        List<String> hashes = repository.findUnusedRecoveryCodeHashes(userId);
        for (String hash : hashes) {
            if (passwordEncoder.matches(normalized, hash)) {
                if (repository.consumeRecoveryCode(userId, hash)) {
                    return requireUser(userId);
                }
                break;
            }
        }
        otpRateLimiter.recordFailure("recovery:" + userId, ip);
        throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid recovery code.");
    }

    public TwoFactorSetupResponse prepareSetup(User user) {
        if (repository.hasEnabledTwoFactor(user.id())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Two-factor authentication is already enabled.");
        }
        byte[] secret = new byte[20];
        RANDOM.nextBytes(secret);
        String encoded = Base32.encodeToString(secret);
        repository.setPendingSecret(user.id(), encoded);
        String uri = Totp.otpauthUri(issuer, user.email(), secret, digits, periodSeconds);
        activityService.admin(user, "TWO_FACTOR_SETUP_REQUESTED", user.name() + " started two-factor authentication setup.");
        return new TwoFactorSetupResponse(encoded, uri);
    }

    public RecoveryCodesResponse confirmSetup(User user, String code) {
        if (repository.hasEnabledTwoFactor(user.id())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Two-factor authentication is already enabled.");
        }
        String pending = repository.findPendingTotpSecret(user.id())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Two-factor setup has not been started."));
        byte[] secret = decodeSecret(pending);
        if (Totp.verify(secret, code, Instant.now(), periodSeconds, digits, Math.max(2, window)).isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid verification code. Check that your authenticator app shows the correct code.");
        }
        repository.confirmTotpSecret(user.id(), pending);
        List<String> recoveryCodes = generateRecoveryCodes();
        List<String> hashed = new ArrayList<>(recoveryCodes.size());
        for (String codeText : recoveryCodes) {
            hashed.add(passwordEncoder.encode(normalizeRecoveryCode(codeText)));
        }
        repository.deleteAllRecoveryCodes(user.id());
        repository.saveRecoveryCodes(user.id(), hashed);
        activityService.admin(user, "TWO_FACTOR_ENABLED", user.name() + " enabled two-factor authentication.");
        activityService.student(user, "TWO_FACTOR_ENABLED", "Two-factor authentication was enabled for your account.");
        return new RecoveryCodesResponse(recoveryCodes);
    }

    public void disable(User user, String code, String password, String ip) {
        if (!repository.hasEnabledTwoFactor(user.id())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Two-factor authentication is not enabled.");
        }
        String key = "disable:" + user.id();
        if (!otpRateLimiter.isAllowed(key, ip)) {
            throw new TooManyRequestsException("Too many attempts. Please try again later.", otpRateLimiter.retryAfterSeconds(key, ip).orElse(1));
        }
        otpRateLimiter.recordAttempt(key, ip);
        boolean authorized = false;
        if (hasText(code)) {
            byte[] secret = repository.findTotpSecret(user.id())
                    .map(this::decodeSecret)
                    .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Session is invalid or expired."));
            authorized = Totp.verify(secret, code, Instant.now(), periodSeconds, digits, 0).isPresent();
        }
        if (!authorized && hasText(password)) {
            authorized = verifyPassword(user.email(), password);
        }
        if (!authorized) {
            otpRateLimiter.recordFailure(key, ip);
            throw new ApiException(HttpStatus.BAD_REQUEST, "A valid verification code or current password is required to disable two-factor authentication.");
        }
        repository.disableTwoFactor(user.id());
        activityService.admin(user, "TWO_FACTOR_DISABLED", user.name() + " disabled two-factor authentication.");
        activityService.student(user, "TWO_FACTOR_DISABLED", "Two-factor authentication was disabled for your account.");
    }

    public boolean status(String userId) {
        return repository.hasEnabledTwoFactor(userId);
    }

    private String consumeChallengeOrThrow(String challengeToken) {
        if (!hasText(challengeToken)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "A verification challenge is required.");
        }
        return repository.consumeChallenge(TwoFactorRepository.sha256Hex(challengeToken), Instant.now())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "The verification session has expired. Sign in again to continue."));
    }

    private void rateLimit(String key, String ip) {
        if (!otpRateLimiter.isAllowed(key, ip)) {
            throw new TooManyRequestsException("Too many one-time code attempts. Please try again later.", otpRateLimiter.retryAfterSeconds(key, ip).orElse(1));
        }
        otpRateLimiter.recordAttempt(key, ip);
    }

    private byte[] decodeSecret(String base32) {
        return Base32.decode(base32);
    }

    private boolean verifyPassword(String email, String password) {
        return userRepository.findByEmailWithPassword(email)
                .map(entry -> entry.passwordHash() != null && !entry.passwordHash().isBlank()
                        && passwordEncoder.matches(password, entry.passwordHash()))
                .orElse(false);
    }

    private User requireUser(String userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Session is invalid or expired."));
    }

    private List<String> generateRecoveryCodes() {
        List<String> codes = new ArrayList<>(recoveryCodeCount);
        for (int index = 0; index < recoveryCodeCount; index++) {
            StringBuilder builder = new StringBuilder(9);
            for (int part = 0; part < 8; part++) {
                if (part == 4) {
                    builder.append('-');
                }
                builder.append(RECOVERY_ALPHABET.charAt(RANDOM.nextInt(RECOVERY_ALPHABET.length())));
            }
            codes.add(builder.toString());
        }
        return codes;
    }

    private String normalizeRecoveryCode(String code) {
        return code == null ? "" : code.replace("-", "").replace(" ", "").trim().toUpperCase();
    }

    private String randomHex(int bytes) {
        byte[] data = new byte[bytes];
        RANDOM.nextBytes(data);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}