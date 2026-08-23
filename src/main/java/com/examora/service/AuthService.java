package com.examora.service;

import com.examora.dto.AuthDtos.AuthResponse;
import com.examora.exception.ApiException;
import com.examora.model.Role;
import com.examora.model.User;
import com.examora.repository.UserRepository;
import com.examora.security.JwtService;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private static final String LEGACY_HASH_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int LEGACY_HASH_BITS = 256;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public AuthResponse login(String email, String password) {
        if (isBlank(email) || isBlank(password)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Email and password are required.");
        }

        UserRepository.UserWithPassword user = userRepository.findByEmailWithPassword(normalizeEmail(email))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Invalid email or password."));
        if (!verifyPassword(password, user.passwordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid email or password.");
        }
        if (isLegacyHash(user.passwordHash())) {
            userRepository.updatePasswordHash(user.user().id(), passwordEncoder.encode(password));
        }
        return new AuthResponse(jwtService.generateToken(user.user()), user.user());
    }

    public AuthResponse register(String name, String email, String password) {
        if (isBlank(name) || isBlank(email) || isBlank(password)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Name, email and password are required.");
        }
        if (password.length() < 8) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Password must be at least 8 characters.");
        }
        try {
            User user = userRepository.create(
                    UUID.randomUUID().toString(),
                    name.trim(),
                    normalizeEmail(email),
                    passwordEncoder.encode(password),
                    Role.STUDENT);
            return new AuthResponse(jwtService.generateToken(user), user);
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "Email is already registered.");
        }
    }

    public void logout(String authorizationHeader) {
        // JWT logout is handled client-side by deleting the token. This endpoint stays safe to call repeatedly.
    }

    public User requireUser(String authorizationHeader) {
        JwtService.JwtClaims claims = jwtService.validate(extractToken(authorizationHeader)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Authentication is required.")));
        return userRepository.findByEmail(claims.email())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Session is invalid or expired."));
    }

    public User requireAdmin(String authorizationHeader) {
        User user = requireUser(authorizationHeader);
        if (user.role() != Role.ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Administrator access is required.");
        }
        return user;
    }

    private boolean verifyPassword(String password, String storedHash) {
        if (isBlank(storedHash)) {
            return false;
        }
        if (storedHash.startsWith("$2a$") || storedHash.startsWith("$2b$") || storedHash.startsWith("$2y$")) {
            return passwordEncoder.matches(password, storedHash);
        }
        return verifyLegacyPbkdf2(password, storedHash);
    }

    private boolean verifyLegacyPbkdf2(String password, String storedHash) {
        if (!isLegacyHash(storedHash)) {
            return false;
        }
        try {
            String[] parts = storedHash.split("\\$");
            if (parts.length != 4) {
                return false;
            }
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = pbkdf2(password.toCharArray(), salt, iterations);
            return java.security.MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private byte[] pbkdf2(char[] password, byte[] salt, int iterations) {
        try {
            KeySpec spec = new PBEKeySpec(password, salt, iterations, LEGACY_HASH_BITS);
            return SecretKeyFactory.getInstance(LEGACY_HASH_ALGORITHM).generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException exception) {
            throw new IllegalStateException("Password hashing is unavailable.", exception);
        }
    }

    private Optional<String> extractToken(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return Optional.empty();
        }
        if (!authorizationHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Optional.empty();
        }
        return Optional.of(authorizationHeader.substring(7).trim()).filter(token -> !token.isBlank());
    }

    private boolean isLegacyHash(String storedHash) {
        return storedHash != null && storedHash.startsWith("pbkdf2$");
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
