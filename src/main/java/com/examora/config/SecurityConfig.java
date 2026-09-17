package com.examora.config;

import com.examora.dto.ApiResponse;
import com.examora.security.JwtAuthenticationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("Examora uses JWT authentication.");
        };
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            ObjectMapper objectMapper) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .headers(headers -> headers
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(frameOptions -> frameOptions.deny())
                        .referrerPolicy(referrerPolicy -> referrerPolicy
                                .policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).preload(true).maxAgeInSeconds(31536000))
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .cacheControl(Customizer.withDefaults()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> writeError(response, objectMapper, HttpServletResponse.SC_UNAUTHORIZED, "Authentication is required."))
                        .accessDeniedHandler((request, response, exception) -> writeError(response, objectMapper, HttpServletResponse.SC_FORBIDDEN, "Access is denied for this role.")))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/ws/**").permitAll()
                        .requestMatchers("/api/auth/login", "/api/auth/register", "/api/auth/logout",
                                "/api/auth/2fa/verify", "/api/auth/2fa/recover", "/api/auth/oauth/**",
                                "/api/status", "/api/db/health").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/student/adaptive/**").hasRole("STUDENT")
                        .requestMatchers(HttpMethod.GET, "/api/student/adaptive/**").hasRole("STUDENT")
                        .requestMatchers("/api/student/ai-practice/**").hasRole("STUDENT")
                        .requestMatchers("/api/student/ai-tutor/**").hasRole("STUDENT")
                        .requestMatchers("/api/student/ai-coach/**").hasRole("STUDENT")
                        .requestMatchers(HttpMethod.GET, "/api/student/learning-profile").hasRole("STUDENT")
                        .requestMatchers(HttpMethod.GET, "/api/student/learning-intelligence").hasRole("STUDENT")
                        .requestMatchers(HttpMethod.GET, "/api/student/progress").hasRole("STUDENT")
                        .requestMatchers(HttpMethod.GET, "/api/student/recommendations").hasRole("STUDENT")
                        .requestMatchers(HttpMethod.GET, "/api/student/analytics/**").hasRole("STUDENT")
                        .requestMatchers("/api/users/me", "/api/student/**").authenticated()
                        .requestMatchers("/api/users/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/exams/*/start", "/api/exams/*/submit").hasRole("STUDENT")
                        .requestMatchers(HttpMethod.GET, "/api/exams/*/result").hasRole("STUDENT")
                        .requestMatchers(HttpMethod.GET, "/api/exams/*/attempt/progress").hasRole("STUDENT")
                        .requestMatchers(HttpMethod.PUT, "/api/exams/*/attempt/answers/*").hasRole("STUDENT")
                        .requestMatchers(HttpMethod.GET, "/api/exams/*/questions", "/api/exams/**").hasAnyRole("STUDENT", "TEACHER", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/questions/**", "/api/options/**").hasAnyRole("TEACHER", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/exams/**", "/api/questions/**", "/api/options/**").hasAnyRole("TEACHER", "ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/exams/**", "/api/questions/**", "/api/options/**").hasAnyRole("TEACHER", "ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/exams/**", "/api/questions/**", "/api/options/**").hasAnyRole("TEACHER", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/results/me").hasAnyRole("STUDENT", "TEACHER", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/answers/**").hasAnyRole("STUDENT", "TEACHER", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/proctor/events/batch").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/proctor/**").hasAnyRole("TEACHER", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/proctor/**").hasAnyRole("TEACHER", "ADMIN")
                        .requestMatchers("/api/analytics/**").hasAnyRole("TEACHER", "ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/answers/**").hasAnyRole("STUDENT", "TEACHER", "ADMIN")
                        .requestMatchers("/api/results/**", "/api/answers/**").hasAnyRole("TEACHER", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/exam-rooms/join").hasRole("STUDENT")
                        .requestMatchers(HttpMethod.GET, "/api/exam-rooms/my").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/exam-rooms/**").authenticated()
                        .requestMatchers("/api/exam-rooms/**").hasAnyRole("TEACHER", "ADMIN")
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    private void writeError(HttpServletResponse response, ObjectMapper objectMapper, int status, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(message));
    }
}
