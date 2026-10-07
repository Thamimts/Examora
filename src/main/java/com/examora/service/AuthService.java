package com.examora.service;

import com.examora.dto.AuthDtos.AuthResponse;
import com.examora.dto.AuthDtos.LoginResult;
import com.examora.exception.ApiException;
import com.examora.model.Role;
import com.examora.model.User;
import com.examora.repository.UserRepository;
import com.examora.security.JwtService;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
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
    private final ActivityService activityService;
    private final TwoFactorService twoFactorService;
    private final String dummyPasswordHash;
    private final boolean adminTwoFactorMandatory;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService,
                       ActivityService activityService, TwoFactorService twoFactorService,
                       @org.springframework.beans.factory.annotation.Value("${examora.admin.two-factor.mandatory:true}") boolean adminTwoFactorMandatory) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.activityService = activityService;
        this.twoFactorService = twoFactorService;
        this.adminTwoFactorMandatory = adminTwoFactorMandatory;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public LoginResult login(String email, String password) {
        if (isBlank(email) || isBlank(password)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Email and password are required.");
        }

        UserRepository.UserWithPassword user = userRepository.findByEmailWithPassword(normalizeEmail(email))
                .orElseThrow(() -> {
                    verifyPassword(password, dummyPasswordHash);
                    return new ApiException(HttpStatus.UNAUTHORIZED, "Invalid email or password.");
                });
        if (!verifyPassword(password, user.passwordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid email or password.");
        }
        if (isLegacyHash(user.passwordHash())) {
            userRepository.updatePasswordHash(user.user().id(), passwordEncoder.encode(password));
        }
        if (adminTwoFactorMandatory && user.user().role() == Role.ADMIN && !twoFactorService.isEnabled(user.user().id())) {
            // Two-factor authentication is mandatory for administrators: do not issue a
            // normal access token until the account is protected and a fresh sign-in
            // completes the second factor.
            return new LoginResult(null, user.user(), false, null, false, true,
                    jwtService.generateSetupToken(user.user()));
        }
        if (twoFactorService.isEnabled(user.user().id())) {
            String challengeToken = twoFactorService.issueChallenge(user.user().id());
            return new LoginResult(null, null, true, challengeToken);
        }
        return new LoginResult(issueToken(user.user()), user.user(), false, null);
    }

    /**
     * First-login academic sign-in. A student who was created with a roll number and date of
     * birth signs in once with those credentials and is forced to set a password. Once a
     * password exists the date of birth is no longer a credential.
     */
    public LoginResult academicFirstLogin(String rollNumber, String dateOfBirth) {
        if (isBlank(rollNumber) || isBlank(dateOfBirth)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Roll number and date of birth are required.");
        }
        LocalDate dob = parseIsoDate(dateOfBirth);
        UserRepository.UserIdentityWithPassword identity = userRepository
                .findByRollNumberWithPassword(rollNumber.trim())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Invalid roll number or date of birth."));
        if (identity.user().role() != Role.STUDENT) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid roll number or date of birth.");
        }
        if (identity.dateOfBirth() == null || !identity.dateOfBirth().equals(dob)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid roll number or date of birth.");
        }
        if (!identity.passwordChangeRequired()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "A password is already set for this account. Sign in with your email address and password.");
        }
        return new LoginResult(issueToken(identity.user()), identity.user(), false, null, true);
    }

    public AuthResponse completeTwoFactor(String challengeToken, String code, String ip) {
        User user = twoFactorService.completeTwoFactor(challengeToken, code, ip);
        recordLogin(user, "LOGIN_SUCCESS_TWO_FACTOR");
        return new AuthResponse(issueToken(user), user);
    }

    public AuthResponse completeRecovery(String challengeToken, String recoveryCode, String ip) {
        User user = twoFactorService.completeRecovery(challengeToken, recoveryCode, ip);
        recordLogin(user, "LOGIN_SUCCESS_RECOVERY");
        return new AuthResponse(issueToken(user), user);
    }

    public void changePassword(User user, String currentPassword, String newPassword) {
        if (isBlank(newPassword) || newPassword.length() < 8) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Password must be at least 8 characters.");
        }
        if (newPassword.equals(currentPassword)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The new password must be different from the current password.");
        }
        String storedHash = userRepository.findByEmailWithPassword(user.email())
                .map(UserRepository.UserWithPassword::passwordHash)
                .orElse(null);
        if (hasText(storedHash) && !verifyPassword(currentPassword, storedHash)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The current password is incorrect.");
        }
        userRepository.updatePasswordHash(user.id(), passwordEncoder.encode(newPassword));
        userRepository.setPasswordChangeRequired(user.id(), false);
        activityService.admin(user, "PASSWORD_CHANGED", user.name() + " changed their password.");
        if (user.role() == Role.STUDENT) {
            activityService.student(user, "PASSWORD_CHANGED", "Your password was changed.");
        }
    }

    private String issueToken(User user) {
        return jwtService.generateToken(user);
    }

    private void recordLogin(User user, String type) {
        if (user.role() == Role.STUDENT) {
            activityService.student(user, type, "You signed in to your account.");
        } else {
            activityService.admin(user, type, user.name() + " signed in.");
        }
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
            activityService.student(user, "REGISTERED", "Your student account was created.");
            activityService.admin("STUDENT_REGISTERED", user.name() + " registered as a student.");
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

    private LocalDate parseIsoDate(String value) {
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Date of birth must use the format YYYY-MM-DD.");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
