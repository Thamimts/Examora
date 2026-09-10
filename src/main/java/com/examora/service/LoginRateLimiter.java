package com.examora.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class LoginRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);

    private final int maxPerIpAndEmail;
    private final int maxPerIp;
    private final long windowMillis;
    private final int maxBuckets;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public LoginRateLimiter(@Value("${examora.login.max-per-ip-email:10}") int maxPerIpAndEmail,
                            @Value("${examora.login.max-per-ip:60}") int maxPerIp,
                            @Value("${examora.login.window-seconds:60}") long windowSeconds,
                            @Value("${examora.login.max-buckets:100000}") int maxBuckets) {
        this.maxPerIpAndEmail = maxPerIpAndEmail;
        this.maxPerIp = maxPerIp;
        this.windowMillis = windowSeconds * 1000L;
        this.maxBuckets = Math.max(1, maxBuckets);
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

    @Scheduled(fixedDelayString = "${examora.login.cleanup-interval-ms:60000}")
    public void scheduledCleanup() {
        int removed = cleanup();
        if (removed > 0) {
            log.debug("Cleaned up {} expired login rate-limit buckets.", removed);
        }
    }

    public int cleanup() {
        long now = System.currentTimeMillis();
        int removed = 0;
        for (Map.Entry<String, Bucket> entry : buckets.entrySet()) {
            Bucket bucket = entry.getValue();
            synchronized (bucket.times) {
                prune(bucket.times, now);
                boolean expired = bucket.times.isEmpty() || bucket.times.peekLast() < now - windowMillis;
                if (expired && buckets.remove(entry.getKey(), bucket)) {
                    removed++;
                }
            }
        }
        return removed;
    }

    private void record(String key) {
        Bucket bucket = buckets.computeIfAbsent(key, ignored -> new Bucket());
        long now = System.currentTimeMillis();
        synchronized (bucket.times) {
            prune(bucket.times, now);
            bucket.times.addLast(now);
            bucket.touch();
        }
        compactIfNeeded();
    }

    private OptionalInt retryAfter(String key, int max) {
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            return OptionalInt.empty();
        }
        synchronized (bucket.times) {
            long now = System.currentTimeMillis();
            prune(bucket.times, now);
            if (bucket.times.size() < max) {
                return OptionalInt.empty();
            }
            long waitMillis = bucket.times.peekFirst() + windowMillis - now;
            int waitSeconds = (int) Math.max(1, Math.ceil(waitMillis / 1000.0));
            return OptionalInt.of(waitSeconds);
        }
    }

    private void compactIfNeeded() {
        if (buckets.size() <= maxBuckets) {
            return;
        }
        cleanup();
        if (buckets.size() <= maxBuckets) {
            return;
        }
        List<Map.Entry<String, Bucket>> byLeastRecentlyUsed = new ArrayList<>(buckets.entrySet());
        byLeastRecentlyUsed.sort((left, right) -> Long.compare(left.getValue().touchedNanos, right.getValue().touchedNanos));
        for (Map.Entry<String, Bucket> entry : byLeastRecentlyUsed) {
            if (buckets.size() <= maxBuckets) {
                break;
            }
            buckets.remove(entry.getKey(), entry.getValue());
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

    static final class Bucket {
        final Deque<Long> times = new ArrayDeque<>();
        volatile long touchedNanos = System.nanoTime();

        void touch() {
            touchedNanos = System.nanoTime();
        }
    }

    int bucketCount() {
        return buckets.size();
    }
}
