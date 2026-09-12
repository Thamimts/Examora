package com.examora.security;

public enum OAuthProvider {
    GOOGLE("Google"),
    GITHUB("GitHub");

    private final String label;

    OAuthProvider(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}