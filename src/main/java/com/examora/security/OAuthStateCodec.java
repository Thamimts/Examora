package com.examora.security;

import com.examora.exception.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Signs and verifies one-time OAuth state values so a callback can only be
 * completed with a challenger-issued state that has not expired.
 */
@Component
public class OAuthStateCodec {
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private final byte[] secret;

    public OAuthStateCodec(@Value("${examora.jwt.secret}") String secret) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 characters for OAuth state signing.");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String create(OAuthProvider provider, String returnTo, int ttlSeconds) {
        String payload = provider.name() + "|" + sanitizeReturnTo(returnTo) + "|" + UUID.randomUUID()
                + "|" + (System.currentTimeMillis() / 1000L + ttlSeconds);
        String data = URL_ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return data + "." + sign(data);
    }

    public State verify(String state, OAuthProvider provider) {
        if (state == null || state.isBlank()) {
            throw unauthorized();
        }
        String[] parts = state.split("\\.");
        if (parts.length != 2 || !constantTimeEquals(sign(parts[0]), parts[1])) {
            throw unauthorized();
        }
        String payload;
        try {
            payload = new String(URL_DECODER.decode(parts[0]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw unauthorized();
        }
        String[] fields = payload.split("\\|", -1);
        if (fields.length != 4 || !fields[0].equals(provider.name())) {
            throw unauthorized();
        }
        long expiresAt;
        try {
            expiresAt = Long.parseLong(fields[3]);
        } catch (NumberFormatException exception) {
            throw unauthorized();
        }
        if (expiresAt <= System.currentTimeMillis() / 1000L) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "The sign-in link has expired. Try again.");
        }
        return new State(fields[1]);
    }

    private String sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return URL_ENCODER.encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to sign OAuth state.", exception);
        }
    }

    private boolean constantTimeEquals(String first, String second) {
        return MessageDigest.isEqual(
                first.getBytes(StandardCharsets.UTF_8),
                second.getBytes(StandardCharsets.UTF_8));
    }

    private String sanitizeReturnTo(String returnTo) {
        if (returnTo == null || returnTo.isBlank() || !returnTo.startsWith("/") || returnTo.contains("\\") || returnTo.contains("..")) {
            return "/oauth/callback";
        }
        return returnTo;
    }

    private ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "The sign-in request is invalid.");
    }

    public record State(String returnTo) {
    }
}