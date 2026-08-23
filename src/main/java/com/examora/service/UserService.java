package com.examora.service;

import com.examora.exception.ApiException;
import com.examora.model.Role;
import com.examora.model.User;
import com.examora.repository.UserRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class UserService {
    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public List<User> findAll() {
        return userRepository.findAll();
    }

    public User findById(String id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found."));
    }

    public User create(User user) {
        User normalized = new User(
                user.id() == null || user.id().isBlank() ? UUID.randomUUID().toString() : user.id(),
                required(user.name(), "Name"),
                required(user.email(), "Email").toLowerCase(),
                user.role() == null ? Role.STUDENT : user.role(),
                user.avatar());
        try {
            return userRepository.create(normalized);
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
