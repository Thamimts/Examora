package com.examora.service;

import com.examora.dto.UserDtos.CreateUserRequest;
import com.examora.dto.UserDtos.ResetStudentCredentialsRequest;
import com.examora.dto.UserDtos.UpdateProfileRequest;
import com.examora.exception.ApiException;
import com.examora.model.Role;
import com.examora.model.User;
import com.examora.model.UserProfile;
import com.examora.repository.UserRepository;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
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

    public UserProfile findProfileById(String id) {
        return userRepository.findProfileById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found."));
    }

    public User create(CreateUserRequest request) {
        String name = required(request.name(), "Name");
        String email = required(request.email(), "Email").toLowerCase();
        Role role = request.role() == null ? Role.STUDENT : request.role();
        String rollNumber = trimToNull(request.rollNumber());
        if (role == Role.STUDENT && rollNumber != null) {
            return createAcademicStudent(name, email, request.password(), rollNumber,
                    parseDate(request.dateOfBirth()), trimToNull(request.department()), trimToNull(request.batch()));
        }
        String password = request.password();
        if (password == null || password.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Password is required.");
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
        }
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

    public UserProfile updateProfile(User owner, UpdateProfileRequest request) {
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Profile fields are required.");
        }
        String name = required(request.name(), "Name");
        String department = trimToNull(request.department());
        String batch = trimToNull(request.batch());
        String avatar = trimToNull(request.avatar());
        userRepository.updateProfileFields(owner.id(), name, department, batch, avatar);
        return findProfileById(owner.id());
    }

    public UserProfile resetStudentCredentials(User actor, String studentId, ResetStudentCredentialsRequest request) {
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Roll number and date of birth are required.");
        }
        User student = findById(studentId);
        if (student.role() != Role.STUDENT) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Only student accounts can have academic credentials reset.");
        }
        String rollNumber = required(request.rollNumber(), "Roll number");
        LocalDate dateOfBirth = parseDate(request.dateOfBirth());
        try {
            userRepository.setRollNumber(student.id(), rollNumber, dateOfBirth);
            userRepository.setPasswordChangeRequired(student.id(), true);
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "Roll number is already registered.");
        }
        return findProfileById(student.id());
    }

    public void delete(String id) {
        if (userRepository.delete(id) == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "User not found.");
        }
    }

    private User createAcademicStudent(String name, String email, String password, String rollNumber,
                                       LocalDate dateOfBirth, String department, String batch) {
        if (dateOfBirth == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Date of birth is required for an academic student account.");
        }
        boolean passwordChangeRequired;
        String passwordHash;
        if (password == null || password.isBlank()) {
            passwordChangeRequired = true;
            passwordHash = null;
        } else {
            if (password.length() < MIN_PASSWORD_LENGTH) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
            }
            passwordChangeRequired = false;
            passwordHash = passwordEncoder.encode(password);
        }
        String id = UUID.randomUUID().toString();
        try {
            userRepository.createAcademicStudent(id, name, email, rollNumber, dateOfBirth,
                    department, batch, passwordHash, passwordChangeRequired);
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "Email is already registered.");
        }
        return findById(id);
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Date of birth must use the format YYYY-MM-DD.");
        }
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, field + " is required.");
        }
        return value.trim();
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
