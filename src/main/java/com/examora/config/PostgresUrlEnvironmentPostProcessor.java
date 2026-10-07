package com.examora.config;

import java.util.Map;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/** Normalizes Render's postgres connection URL before Spring creates the datasource. */
public final class PostgresUrlEnvironmentPostProcessor implements EnvironmentPostProcessor {
    private static final String JDBC_PREFIX = "jdbc:postgresql://";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, org.springframework.boot.SpringApplication application) {
        String configuredUrl = environment.getProperty("DB_URL");
        if (configuredUrl == null || configuredUrl.isBlank()) {
            configuredUrl = environment.getProperty("spring.datasource.url");
        }

        String normalizedUrl = normalize(configuredUrl);
        if (normalizedUrl != null && !normalizedUrl.equals(configuredUrl)) {
            environment.getPropertySources().addFirst(
                    new MapPropertySource("examoraPostgresUrl", Map.of("spring.datasource.url", normalizedUrl)));
        }
    }

    static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }

        String url = value.trim();
        if (url.startsWith("postgresql://")) {
            url = "jdbc:" + url;
        }
        if (!url.startsWith(JDBC_PREFIX)) {
            return value;
        }

        String remainder = url.substring(JDBC_PREFIX.length());
        int pathStart = firstIndexOf(remainder, '/', '?');
        String authority = pathStart < 0 ? remainder : remainder.substring(0, pathStart);
        String suffix = pathStart < 0 ? "" : remainder.substring(pathStart);

        int credentialsEnd = authority.lastIndexOf('@');
        if (credentialsEnd >= 0) {
            authority = authority.substring(credentialsEnd + 1);
        }
        if (!hasPort(authority)) {
            authority += ":5432";
        }
        return JDBC_PREFIX + authority + suffix;
    }

    private static int firstIndexOf(String value, char first, char second) {
        int firstIndex = value.indexOf(first);
        int secondIndex = value.indexOf(second);
        if (firstIndex < 0) {
            return secondIndex;
        }
        if (secondIndex < 0) {
            return firstIndex;
        }
        return Math.min(firstIndex, secondIndex);
    }

    private static boolean hasPort(String authority) {
        if (authority.startsWith("[")) {
            int closingBracket = authority.indexOf(']');
            return closingBracket >= 0 && authority.length() > closingBracket + 1
                    && authority.charAt(closingBracket + 1) == ':';
        }
        return authority.lastIndexOf(':') >= 0;
    }
}