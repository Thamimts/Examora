package com.examora.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.OptionalInt;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * RFC 6238 time-based one-time passwords (Google Authenticator compatible, HMAC-SHA1).
 */
public final class Totp {
    private static final String HMAC_ALGORITHM = "HmacSHA1";

    private Totp() {
    }

    public static String generate(byte[] secret, Instant time, int periodSeconds, int digits) {
        long counter = time.getEpochSecond() / Math.max(1, periodSeconds);
        byte[] hash = hmacSha1(secret, counter);
        int offset = hash[hash.length - 1] & 0x0F;
        int binary = ((hash[offset] & 0x7F) << 24)
                | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8)
                | (hash[offset + 3] & 0xFF);
        int code = binary % pow10(digits);
        return String.format("%0" + digits + "d", code);
    }

    /**
     * Verifies a code against the current and surrounding time steps.
     *
     * @return the matching time-step delta (0 = current window) or an empty OptionalInt.
     */
    public static OptionalInt verify(byte[] secret, String code, Instant time, int periodSeconds, int digits, int window) {
        String normalized = code == null ? "" : code.trim();
        if (!normalized.matches("[0-9]{" + Math.max(1, digits) + "}")) {
            return OptionalInt.empty();
        }
        for (int offset = -window; offset <= window; offset++) {
            Instant candidateTime = time.plusSeconds(offset * (long) periodSeconds);
            if (generate(secret, candidateTime, periodSeconds, digits).equals(normalized)) {
                return OptionalInt.of(offset);
            }
        }
        return OptionalInt.empty();
    }

    public static String otpauthUri(String issuer, String account, byte[] secret, int digits, int periodSeconds) {
        String label = urlEncode(issuer.trim()) + ":" + urlEncode(account.trim());
        return "otpauth://totp/" + label
                + "?secret=" + Base32.encodeToString(secret)
                + "&issuer=" + urlEncode(issuer.trim())
                + "&algorithm=SHA1&digits=" + digits + "&period=" + periodSeconds;
    }

    private static byte[] hmacSha1(byte[] secret, long counter) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            byte[] data = new byte[8];
            for (int index = 7; index >= 0; index--) {
                data[index] = (byte) (counter & 0xFFL);
                counter >>>= 8;
            }
            return mac.doFinal(data);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("TOTP generation is unavailable.", exception);
        }
    }

    private static int pow10(int digits) {
        int value = 1;
        for (int index = 0; index < digits; index++) {
            value *= 10;
        }
        return value;
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%2F", "/")
                .replace("%3A", ":");
    }
}