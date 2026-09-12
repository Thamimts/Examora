package com.examora.security;

public record OAuthUserInfo(OAuthProvider provider, String providerUserId, String email, String name, boolean emailVerified) {
}