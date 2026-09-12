package com.examora.service;

import com.examora.config.OAuthConfig;
import com.examora.dto.AuthDtos.AuthResponse;
import com.examora.exception.ApiException;
import com.examora.exception.TooManyRequestsException;
import com.examora.model.OAuthAccount;
import com.examora.model.User;
import com.examora.repository.OAuthAccountRepository;
import com.examora.repository.UserRepository;
import com.examora.security.JwtService;
import com.examora.security.OAuthProvider;
import com.examora.security.OAuthProviderClient;
import com.examora.security.OAuthStateCodec;
import com.examora.security.OAuthUserInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class OAuthService {
    private final OAuthConfig config;
    private final OAuthStateCodec stateCodec;
    private final Map<String, OAuthProviderClient> clients;
    private final UserRepository userRepository;
    private final OAuthAccountRepository accountRepository;
    private final JwtService jwtService;
    private final ActivityService activityService;
    private final LoginRateLimiter loginRateLimiter;
    private final OtpRateLimiter otpRateLimiter;

    public OAuthService(OAuthConfig config,
                        OAuthStateCodec stateCodec,
                        Map<String, OAuthProviderClient> clients,
                        UserRepository userRepository,
                        OAuthAccountRepository accountRepository,
                        JwtService jwtService,
                        ActivityService activityService,
                        LoginRateLimiter loginRateLimiter,
                        OtpRateLimiter otpRateLimiter) {
        this.config = config;
        this.stateCodec = stateCodec;
        this.clients = clients;
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.jwtService = jwtService;
        this.activityService = activityService;
        this.loginRateLimiter = loginRateLimiter;
        this.otpRateLimiter = otpRateLimiter;
    }

    public List<ProviderAvailability> availableProviders() {
        List<ProviderAvailability> result = new ArrayList<>();
        for (OAuthProvider provider : OAuthProvider.values()) {
            result.add(new ProviderAvailability(provider.name(), provider.label(), config.isConfigured(provider)));
        }
        return result;
    }

    public boolean isEnabled(OAuthProvider provider) {
        return config.isConfigured(provider);
    }

    public String startLoginUrl(OAuthProvider provider, String returnTo, String ip) {
        requireConfigured(provider);
        String key = "oauth-start";
        if (!loginRateLimiter.isAllowed(ip, key)) {
            throw new TooManyRequestsException("Too many sign-in attempts. Please try again later.", loginRateLimiter.retryAfterSeconds(ip, key).orElse(1));
        }
        loginRateLimiter.recordAttempt(ip, key);
        String state = stateCodec.create(provider, returnTo, config.stateTtlSeconds());
        return client(provider).buildAuthorizeUrl(state);
    }

    public OAuthCallbackResult completeCallback(OAuthProvider provider, String code, String state, String ip) {
        requireConfigured(provider);
        String key = "oauth-callback:" + ip;
        if (!otpRateLimiter.isAllowed(key, ip)) {
            throw new TooManyRequestsException("Too many sign-in attempts. Please try again later.", otpRateLimiter.retryAfterSeconds(key, ip).orElse(1));
        }
        otpRateLimiter.recordAttempt(key, ip);
        OAuthStateCodec.State verifiedState = stateCodec.verify(state, provider);
        OAuthUserInfo info = client(provider).exchangeCode(code, config.config(provider).redirectUri());
        if (info.email() == null || info.email().isBlank()) {
            otpRateLimiter.recordFailure(key, ip);
            throw new ApiException(HttpStatus.BAD_REQUEST, "The sign-in service did not provide a valid email address.");
        }
        return new OAuthCallbackResult(finalizeOAuth(info.provider(), info.providerUserId(), info.email(), info.name()), verifiedState.returnTo());
    }

    /**
     * Resolves an OAuth identity to an Examora account and returns a session. The
     * sign-in call is deliberately on this path so tests can exercise the
     * account-linking rules without a live provider exchange.
     */
    public AuthResponse finalizeOAuth(OAuthProvider provider, String providerUserId, String email, String name) {
        Optional<OAuthAccount> existing = accountRepository.findByProviderAndProviderUserId(provider.name(), providerUserId);
        if (existing.isPresent()) {
            User user = userRepository.findById(existing.get().userId())
                    .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Your linked account no longer exists."));
            activityService.admin(user, "OAUTH_LOGIN_SUCCESS", user.name() + " signed in with " + provider.label() + ".");
            return new AuthResponse(jwtService.generateToken(user), user);
        }
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        Optional<User> existingUser = userRepository.findByEmail(normalizedEmail);
        User user = existingUser.orElseGet(() -> createUser(provider, normalizedEmail, name));
        boolean createdNew = existingUser.isEmpty();
        if (accountRepository.findByProviderAndUserId(provider.name(), user.id()).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "This " + provider.label() + " account is already linked to another sign-in.");
        }
        try {
            accountRepository.create(provider.name(), providerUserId, user.id(), normalizedEmail);
        } catch (DuplicateKeyException exception) {
            return handleProviderRace(provider, providerUserId, normalizedEmail);
        }
        activityService.admin(user, createdNew ? "OAUTH_ACCOUNT_CREATED" : "OAUTH_ACCOUNT_LINKED",
                user.name() + " linked " + provider.label() + " to their account.");
        return new AuthResponse(jwtService.generateToken(user), user);
    }

    private AuthResponse handleProviderRace(OAuthProvider provider, String providerUserId, String email) {
        OAuthAccount account = accountRepository.findByProviderAndProviderUserId(provider.name(), providerUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "The sign-in could not be linked to an account."));
        User user = userRepository.findById(account.userId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Your linked account no longer exists."));
        return new AuthResponse(jwtService.generateToken(user), user);
    }

    private User createUser(OAuthProvider provider, String email, String name) {
        User user = userRepository.create(
                UUID.randomUUID().toString(),
                defaultName(email, name),
                email,
                null,
                config.defaultRole());
        activityService.admin("STUDENT_REGISTERED", user.name() + " registered via " + provider.label() + ".");
        return user;
    }

    private String defaultName(String email, String name) {
        if (name != null && !name.isBlank()) {
            return name.trim().substring(0, Math.min(120, name.trim().length()));
        }
        String local = email.substring(0, email.indexOf('@') >= 0 ? email.indexOf('@') : email.length());
        return local.length() > 1 ? local.charAt(0) + local.substring(1) : local;
    }

    private OAuthProviderClient client(OAuthProvider provider) {
        OAuthProviderClient client = clients.get(provider.name().toLowerCase(Locale.ROOT));
        if (client == null) {
            throw new ApiException(HttpStatus.NOT_IMPLEMENTED, "This sign-in provider is not configured.");
        }
        return client;
    }

    private void requireConfigured(OAuthProvider provider) {
        if (!config.isConfigured(provider)) {
            throw new ApiException(HttpStatus.NOT_IMPLEMENTED, "Sign in with " + provider.label() + " is not configured.");
        }
    }

    public record ProviderAvailability(String provider, String label, boolean enabled) {
    }

    public record OAuthCallbackResult(AuthResponse auth, String returnTo) {
    }
}