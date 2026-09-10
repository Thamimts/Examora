package com.examora.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LoginRateLimiter {
    private final int maxPerIpAndEmail;
    private final int maxPerIp;
    private final long windowMillis;

    private final Map<String, Deque<Long>> buckets = new ConcurrentHashMap<>();

    public LoginRateLimiter(@Value("${examora.login.max-per-ip-email:10}") int maxPerIpAndEmail,
                            @Value("${examora.login.max-per-ip:60}") int maxPerIp,
                            @Value("${examora.login.window-seconds:60}") long windowSeconds) {
        this.maxPerIpAndEmail = maxPerIpAndEmail;
        this.maxPerIp = maxPerIp;
        this.windowMillis = windowSeconds * 1000L;
    }

    public boolean isAllowed(String ip, String email) {
        return retryAfterSeconds(ip, email).isEmpty();
    }

    public OptionalInt retryAfterSeconds(String ip, String email) {
        OptionalInt perPair = retryAfter(bucketKey(ip, email), maxPerIpAndEmail);
        if (perPair.isPresent()) {
            return perPair;
        }
        return retryAfter(bucketKey(ip), maxPerIp);
    }

    public void recordAttempt(String ip, String email) {
        record(bucketKey(ip));
    }

    public void recordFailure(String ip, String email) {
        record(bucketKey(ip, email));
    }

    private void record(String key) {
        Deque<Long> times = buckets.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        synchronized (times) {
            prune(times, now);
            times.addLast(now);
        }
    }

    private OptionalInt retryAfter(String key, int max) {
        Deque<Long> times = buckets.get(key);
        if (times == null) {
            return OptionalInt.empty();
        }
        synchronized (times) {
            long now = System.currentTimeMillis();
            prune(times, now);
            if (times.size() < max) {
                return OptionalInt.empty();
            }
            long waitMillis = times.peekFirst() + windowMillis - now;
            int waitSeconds = (int) Math.max(1, Math.ceil(waitMillis / 1000.0));
            return OptionalInt.of(waitSeconds);
        }
    }

    private void prune(Deque<Long> times, long now) {
        long cutoff = now - windowMillis;
        while (!times.isEmpty() && times.peekFirst() < cutoff) {
            times.pollFirst();
        }
    }

    private String bucketKey(String... parts) {
        return String.join("|", parts);
    }
}