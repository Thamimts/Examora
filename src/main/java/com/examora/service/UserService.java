package com.examora.service;

import com.examora.dto.UserDtos.CreateUserRequest;
import com.examora.exception.ApiException;
import com.examora.model.Role;
import com.examora.model.User;
import com.examora.repository.UserRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserService {
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public List<User> findAll() {
        return userRepository.findAll();
    }

    public User findById(String id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found."));
    }

    public User create(CreateUserRequest request) {
        String name = required(request.name(), "Name");
        String email = required(request.email(), "Email").toLowerCase();
        String password = request.password();
        if (password == null || password.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Password is required.");
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
        }
        Role role = request.role() == null ? Role.STUDENT : request.role();
        try {
            return userRepository.create(
                    UUID.randomUUID().toString(),
                    name,
                    email,
                    passwordEncoder.encode(password),
                    role);
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "Email is already registered.");
        }
    }

    public User update(String id, User user) {
        findById(id);
        User normalized = new User(
                id,
                required(user.name(), "Name"),
                required(user.email(), "Email").toLowerCase(),
                user.role() == null ? Role.STUDENT : user.role(),
                user.avatar());
        try {
            userRepository.update(id, normalized);
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "Email is already registered.");
        }
        return findById(id);
    }

    public void delete(String id) {
        if (userRepository.delete(id) == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "User not found.");
        }
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, field + " is required.");
        }
        return value.trim();
    }
}
