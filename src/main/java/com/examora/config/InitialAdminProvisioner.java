package com.examora.config;

import com.examora.model.Role;
import com.examora.repository.UserRepository;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Provisions one administrator only when both bootstrap environment values are supplied. */
@Component
public class InitialAdminProvisioner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(InitialAdminProvisioner.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String email;
    private final String password;

    public InitialAdminProvisioner(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Value("${EXAMORA_INITIAL_ADMIN_EMAIL:}") String email,
            @Value("${EXAMORA_INITIAL_ADMIN_PASSWORD:}") String password) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.email = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        this.password = password == null ? "" : password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (email.isBlank() && password.isBlank()) {
            return;
        }
        if (email.isBlank() || password.length() < 12) {
            throw new IllegalStateException("EXAMORA_INITIAL_ADMIN_EMAIL and a 12+ character EXAMORA_INITIAL_ADMIN_PASSWORD are required together.");
        }
        if (userRepository.findByEmail(email).isPresent()) {
            return;
        }
        try {
            userRepository.create(UUID.randomUUID().toString(), "Initial Administrator", email,
                    passwordEncoder.encode(password), Role.ADMIN);
            log.info("Initial administrator account provisioned for {}.", email);
        } catch (DuplicateKeyException ignored) {
            // Another application instance provisioned the same account first.
        }
    }
}
