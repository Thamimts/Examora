package com.examora.model;

import java.time.Instant;

public record OAuthAccount(String id, String provider, String providerUserId, String userId, String email, Instant createdAt) {
}