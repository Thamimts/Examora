package com.examora.repository;

import com.examora.model.OAuthAccount;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OAuthAccountRepository {
    private final JdbcTemplate jdbcTemplate;

    public OAuthAccountRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<OAuthAccount> findByProviderAndProviderUserId(String provider, String providerUserId) {
        return find("select id, provider, provider_user_id, user_id, email, created_at from oauth_accounts where provider = ? and provider_user_id = ?", provider, providerUserId);
    }

    public Optional<OAuthAccount> findByProviderAndUserId(String provider, String userId) {
        return find("select id, provider, provider_user_id, user_id, email, created_at from oauth_accounts where provider = ? and user_id = ?", provider, userId);
    }

    public OAuthAccount create(String provider, String providerUserId, String userId, String email) {
        String id = java.util.UUID.randomUUID().toString();
        jdbcTemplate.update(
                "insert into oauth_accounts (id, provider, provider_user_id, user_id, email, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?)",
                id,
                provider,
                providerUserId,
                userId,
                email,
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()));
        return new OAuthAccount(id, provider, providerUserId, userId, email, Instant.now());
    }

    public int deleteByProviderAndUserId(String provider, String userId) {
        return jdbcTemplate.update("delete from oauth_accounts where provider = ? and user_id = ?", provider, userId);
    }

    private Optional<OAuthAccount> find(String sql, Object... args) {
        return jdbcTemplate.query(sql, (rs, row) -> new OAuthAccount(
                        rs.getString("id"),
                        rs.getString("provider"),
                        rs.getString("provider_user_id"),
                        rs.getString("user_id"),
                        rs.getString("email"),
                        rs.getTimestamp("created_at").toInstant()),
                args)
                .stream()
                .findFirst();
    }
}