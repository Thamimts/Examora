package com.examora.repository;

import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TwoFactorRepository {
    private final JdbcTemplate jdbcTemplate;

    public TwoFactorRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean hasEnabledTwoFactor(String userId) {
        Boolean enabled = jdbcTemplate.query(
                "select two_factor_enabled from users where id = ?",
                rs -> rs.next() ? rs.getBoolean("two_factor_enabled") : null,
                userId);
        return Boolean.TRUE.equals(enabled);
    }

    public Optional<String> findTotpSecret(String userId) {
        return jdbcTemplate.query(
                        "select two_factor_secret from users where id = ? and two_factor_enabled = true",
                        (rs, row) -> rs.getString("two_factor_secret"),
                        userId)
                .stream()
                .findFirst()
                .filter(secret -> secret != null && !secret.isBlank());
    }

    public Optional<String> findPendingTotpSecret(String userId) {
        return jdbcTemplate.query(
                        "select two_factor_pending_secret from users where id = ? and two_factor_enabled = false",
                        (rs, row) -> rs.getString("two_factor_pending_secret"),
                        userId)
                .stream()
                .findFirst()
                .filter(secret -> secret != null && !secret.isBlank());
    }

    public void setPendingSecret(String userId, String secret) {
        jdbcTemplate.update("update users set two_factor_pending_secret = ? where id = ?", secret, userId);
    }

    public void confirmTotpSecret(String userId, String secret) {
        jdbcTemplate.update(
                "update users set two_factor_secret = ?, two_factor_pending_secret = null, two_factor_enabled = true where id = ?",
                secret,
                userId);
    }

    public void disableTwoFactor(String userId) {
        jdbcTemplate.update(
                "update users set two_factor_enabled = false, two_factor_secret = null, two_factor_pending_secret = null where id = ?",
                userId);
        deleteAllRecoveryCodes(userId);
        deleteChallenges(userId);
    }

    public void createChallenge(String id, String userId, String tokenHash, String kind, Instant expiresAt) {
        jdbcTemplate.update(
                "insert into two_factor_challenges (id, user_id, token_hash, kind, expires_at, used) values (?, ?, ?, ?, ?, false)",
                id,
                userId,
                tokenHash,
                kind,
                Timestamp.from(expiresAt));
    }

    /**
     * Atomically claims a matching challenge. Returns the owning user id when the
     * challenge existed, was unused and had not expired.
     */
    public Optional<String> consumeChallenge(String tokenHash, Instant now) {
        int updated = jdbcTemplate.update(
                "update two_factor_challenges set used = true where token_hash = ? and used = false and expires_at >= ?",
                tokenHash,
                Timestamp.from(now));
        if (updated != 1) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
                        "select user_id from two_factor_challenges where token_hash = ?",
                        (rs, row) -> rs.getString("user_id"),
                        tokenHash)
                .stream()
                .findFirst();
    }

    public void deleteChallenges(String userId) {
        jdbcTemplate.update("delete from two_factor_challenges where user_id = ?", userId);
    }

    public void deleteExpiredChallenges(String userId, Instant now) {
        jdbcTemplate.update(
                "delete from two_factor_challenges where user_id = ? and used = false and expires_at < ?",
                userId,
                Timestamp.from(now));
    }

    public void saveRecoveryCodes(String userId, List<String> hashes) {
        for (String hash : hashes) {
            jdbcTemplate.update(
                    "insert into two_factor_recovery_codes (id, user_id, code_hash) values (?, ?, ?)",
                    java.util.UUID.randomUUID().toString(),
                    userId,
                    hash);
        }
    }

    public List<String> findUnusedRecoveryCodeHashes(String userId) {
        return jdbcTemplate.query(
                "select code_hash from two_factor_recovery_codes where user_id = ? and used_at is null",
                (rs, row) -> rs.getString("code_hash"),
                userId);
    }

    public boolean consumeRecoveryCode(String userId, String codeHash) {
        return jdbcTemplate.update(
                "update two_factor_recovery_codes set used_at = current_timestamp where user_id = ? and code_hash = ? and used_at is null",
                userId,
                codeHash) == 1;
    }

    public int countUnusedRecoveryCodes(String userId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from two_factor_recovery_codes where user_id = ? and used_at is null",
                Integer.class,
                userId);
        return count == null ? 0 : count;
    }

    public void deleteAllRecoveryCodes(String userId) {
        jdbcTemplate.update("delete from two_factor_recovery_codes where user_id = ?", userId);
    }

    public static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte part : digest) {
                result.append(Character.forDigit((part >> 4) & 0x0F, 16));
                result.append(Character.forDigit(part & 0x0F, 16));
            }
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}