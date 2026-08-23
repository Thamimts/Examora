package com.examora.security;

import com.examora.exception.ApiException;
import com.examora.model.User;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private final ObjectMapper objectMapper;
    private final byte[] secret;
    private final long expirationHours;

    public JwtService(
            ObjectMapper objectMapper,
            @Value("${examora.jwt.secret}") String secret,
            @Value("${examora.jwt.expiration-hours:24}") long expirationHours) {
        this.objectMapper = objectMapper;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.expirationHours = expirationHours;
    }

    public String generateToken(User user) {
        try {
            Map<String, Object> header = Map.of("alg", "HS256", "typ", "JWT");
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("sub", user.email());
            claims.put("userId", user.id());
            claims.put("role", user.role().name());
            claims.put("name", user.name());
            claims.put("iat", Instant.now().getEpochSecond());
            claims.put("exp", Instant.now().plusSeconds(expirationHours * 3600).getEpochSecond());

            String encodedHeader = encodeJson(header);
            String encodedPayload = encodeJson(claims);
            String unsignedToken = encodedHeader + "." + encodedPayload;
            return unsignedToken + "." + sign(unsignedToken);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create authentication token.", exception);
        }
    }

    public JwtClaims validate(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                throw unauthorized();
            }
            String unsignedToken = parts[0] + "." + parts[1];
            if (!constantTimeEquals(sign(unsignedToken), parts[2])) {
                throw unauthorized();
            }

            Map<String, Object> claims = objectMapper.readValue(
                    URL_DECODER.decode(parts[1]),
                    new TypeReference<Map<String, Object>>() {
                    });
            long expiresAt = numberClaim(claims.get("exp"));
            if (expiresAt <= Instant.now().getEpochSecond()) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "Session has expired.");
            }
            String email = stringClaim(claims.get("sub"));
            String role = stringClaim(claims.get("role"));
            if (email == null || role == null) {
                throw unauthorized();
            }
            return new JwtClaims(email, role);
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw unauthorized();
        }
    }

    private String encodeJson(Map<String, Object> value) throws Exception {
        return URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(value));
    }

    private String sign(String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return URL_ENCODER.encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    private boolean constantTimeEquals(String first, String second) {
        return java.security.MessageDigest.isEqual(
                first.getBytes(StandardCharsets.UTF_8),
                second.getBytes(StandardCharsets.UTF_8));
    }

    private long numberClaim(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            return Long.parseLong(text);
        }
        return 0;
    }

    private String stringClaim(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "Session is invalid or expired.");
    }

    public record JwtClaims(String email, String role) {
    }
}
