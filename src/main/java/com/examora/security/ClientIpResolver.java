package com.examora.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves the real client IP for rate limiting.
 *
 * <p>X-Forwarded-For is honored ONLY when {@code examora.proxy-trusted} is enabled by the
 * operator, i.e. when the application is known to sit behind a reverse proxy. By default the
 * header is ignored entirely, so a direct client cannot spoof a different identity. In trusted
 * mode the rightmost value that is not one of the configured {@code examora.proxy.trusted-addresses}
 * is used, since leftmost values may be attacker-controlled.
 */
@Component
public class ClientIpResolver {
    private final boolean proxyTrusted;
    private final Set<String> trustedProxyAddresses;

    public ClientIpResolver(
            @Value("${examora.proxy-trusted:false}") boolean proxyTrusted,
            @Value("${examora.proxy.trusted-addresses:}") List<String> trustedProxyAddresses) {
        this.proxyTrusted = proxyTrusted;
        this.trustedProxyAddresses = trustedProxyAddresses.stream()
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public String resolve(HttpServletRequest request) {
        String remote = request.getRemoteAddr();

        if (proxyTrusted) {
            String forwarded = request.getHeader("X-Forwarded-For");
            boolean fromTrustedProxy = trustedProxyAddresses.isEmpty() || trustedProxyAddresses.contains(remote);
            if (forwarded != null && !forwarded.isBlank() && fromTrustedProxy) {
                String[] entries = forwarded.split(",");
                for (int index = entries.length - 1; index >= 0; index--) {
                    String candidate = entries[index] == null ? "" : entries[index].trim();
                    if (!candidate.isEmpty() && !trustedProxyAddresses.contains(candidate)) {
                        return candidate;
                    }
                }
                return remote;
            }
        }

        return remote == null ? "unknown" : remote;
    }
}