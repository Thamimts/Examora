package com.examora.model;

public record User(String id, String name, String email, Role role, String avatar) {
}
