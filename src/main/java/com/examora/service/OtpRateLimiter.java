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

/**
 * Sliding-window rate limiter for one-time passwords, recovery codes and OAuth
 * state exchange. Keys are caller-supplied (for example "otp:{userId}:{ip}");
 * each key is limited to {@code maxAttempts} per window and every key also
 * counts toward the shared per-IP cap in {@code maxPerIp}, mirroring the login
 * rate limiter to prevent account spraying.
 */
@Component
public class OtpRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(OtpRateLimiter.class);

    private final int maxAttempts;
    private final long windowMillis;
    private final int maxPerIp;
    private final int maxBuckets;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public OtpRateLimiter(@Value("${examora.one-time.max-attempts:5}") int maxAttempts,
                          @Value("${examora.one-time.window-seconds:900}") long windowSeconds,
                          @Value("${examora.one-time.max-per-ip:30}") int maxPerIp,
                          @Value("${examora.one-time.max-buckets:100000}") int maxBuckets) {
        this.maxAttempts = maxAttempts;
        this.windowMillis = windowSeconds * 1000L;
        this.maxPerIp = maxPerIp;
        this.maxBuckets = Math.max(1, maxBuckets);
    }

    public boolean isAllowed(String key, String ip) {
        return retryAfterSeconds(key, ip).isEmpty();
    }

    public OptionalInt retryAfterSeconds(String key, String ip) {
        OptionalInt perKey = retryAfter(bucketKey(key), maxAttempts);
        if (perKey.isPresent()) {
            return perKey;
        }
        return retryAfter(bucketKey("ip", ip), maxPerIp);
    }

    public void recordAttempt(String key, String ip) {
        record(bucketKey("ip", ip));
    }

    public void recordFailure(String key, String ip) {
        record(bucketKey(key));
    }

    @Scheduled(fixedDelayString = "${examora.one-time.cleanup-interval-ms:60000}")
    public void scheduledCleanup() {
        int removed = cleanup();
        if (removed > 0) {
            log.debug("Cleaned up {} expired one-time rate-limit buckets.", removed);
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