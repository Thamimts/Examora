package com.examora.security;

/**
 * Minimal RFC 4648 Base32 codec used for TOTP shared secrets (Google Authenticator style).
 */
public final class Base32 {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int[] REVERSE = new int[128];

    static {
        for (int index = 0; index < REVERSE.length; index++) {
            REVERSE[index] = -1;
        }
        for (int index = 0; index < ALPHABET.length(); index++) {
            REVERSE[ALPHABET.charAt(index)] = index;
            REVERSE[Character.toLowerCase(ALPHABET.charAt(index))] = index;
        }
    }

    private Base32() {
    }

    public static String encodeToString(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        StringBuilder result = new StringBuilder(((data.length + 4) / 5) * 8);
        int buffer = 0;
        int bits = 0;
        for (byte value : data) {
            buffer = (buffer << 8) | (value & 0xFF);
            bits += 8;
            while (bits >= 5) {
                result.append(ALPHABET.charAt((buffer >>> (bits - 5)) & 0x1F));
                bits -= 5;
            }
        }
        if (bits > 0) {
            result.append(ALPHABET.charAt((buffer << (5 - bits)) & 0x1F));
        }
        return result.toString();
    }

    public static byte[] decode(String value) {
        if (value == null) {
            return new byte[0];
        }
        String cleaned = value.replace("=", "").replace(" ", "").trim().toUpperCase();
        int buffer = 0;
        int bits = 0;
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        for (int index = 0; index < cleaned.length(); index++) {
            int digit = cleaned.charAt(index) < REVERSE.length ? REVERSE[cleaned.charAt(index)] : -1;
            if (digit < 0) {
                throw new IllegalArgumentException("Invalid Base32 character: " + cleaned.charAt(index));
            }
            buffer = (buffer << 5) | digit;
            bits += 5;
            if (bits >= 8) {
                output.write((buffer >>> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return output.toByteArray();
    }
}