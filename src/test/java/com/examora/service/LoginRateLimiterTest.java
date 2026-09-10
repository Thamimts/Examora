package com.examora.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LoginRateLimiterTest {
    private static final int MAX_PAIR = 3;
    private static final int MAX_IP = 8;
    private static final long WINDOW_SECONDS = 1;

    @Test
    void challengesLoginWhenLimitReached() {
        LoginRateLimiter limiter = new LoginRateLimiter(MAX_PAIR, MAX_IP, WINDOW_SECONDS, 1000);
        for (int attempt = 0; attempt < MAX_PAIR - 1; attempt++) {
            limiter.recordFailure("203.0.113.1", "a@example.com");
            assertThat(limiter.isAllowed("203.0.113.1", "a@example.com"))
                    .as("allowed before limit is reached").isTrue();
        }
        limiter.recordFailure("203.0.113.1", "a@example.com");
        assertThat(limiter.isAllowed("203.0.113.1", "a@example.com"))
                .as("blocked after limit is reached").isFalse();
        assertThat(limiter.retryAfterSeconds("203.0.113.1", "a@example.com")).isPresent();
    }

    @Test
    void cleanupRemovesExpiredBuckets() throws Exception {
        LoginRateLimiter limiter = new LoginRateLimiter(MAX_PAIR, MAX_IP, WINDOW_SECONDS, 1000);
        limiter.recordAttempt("203.0.113.2", "b@example.com");
        limiter.recordFailure("203.0.113.2", "b@example.com");
        assertThat(limiter.bucketCount()).isEqualTo(2);

        Thread.sleep(1_100);
        assertThat(limiter.cleanup()).as("expired buckets removed").isEqualTo(2);
        assertThat(limiter.bucketCount()).isZero();
        assertThat(limiter.isAllowed("203.0.113.2", "b@example.com")).isTrue();
    }

    @Test
    void cleanupKeepsActiveBucketsAndRemovesOnlyStaleOnes() throws Exception {
        LoginRateLimiter limiter = new LoginRateLimiter(MAX_PAIR, MAX_IP, WINDOW_SECONDS, 1000);
        limiter.recordFailure("203.0.113.3", "c@example.com");
        Thread.sleep(1_100);
        limiter.recordFailure("203.0.113.4", "d@example.com");
        limiter.recordFailure("203.0.113.4", "d@example.com");
        limiter.recordFailure("203.0.113.4", "d@example.com");

        assertThat(limiter.cleanup()).as("only the stale pair is removed").isEqualTo(1);
        assertThat(limiter.bucketCount()).isEqualTo(1);
        assertThat(limiter.isAllowed("203.0.113.4", "d@example.com")).isFalse();
    }

    @Test
    void retryAfterPrunesExpiredEntriesEvenBeforeCleanupRuns() throws Exception {
        LoginRateLimiter limiter = new LoginRateLimiter(MAX_PAIR, MAX_IP, WINDOW_SECONDS, 1000);
        for (int attempt = 0; attempt < MAX_PAIR; attempt++) {
            limiter.recordFailure("203.0.113.5", "e@example.com");
        }
        assertThat(limiter.isAllowed("203.0.113.5", "e@example.com")).isFalse();

        Thread.sleep(1_100);
        assertThat(limiter.isAllowed("203.0.113.5", "e@example.com"))
                .as("window elapsed before cleanup").isTrue();
        assertThat(limiter.bucketCount()).as("expired buckets still tracked until cleanup").isEqualTo(1);
    }

    @Test
    void bucketGrowthIsBoundedByMaximum() {
        LoginRateLimiter limiter = new LoginRateLimiter(MAX_PAIR, MAX_IP, 60, 3);
        for (int index = 0; index < 50; index++) {
            limiter.recordAttempt("203.0.113." + (index % 250 + 10), "u" + index + "@example.com");
        }
        assertThat(limiter.bucketCount()).isLessThanOrEqualTo(3);

        for (int attempt = 0; attempt < MAX_IP; attempt++) {
            limiter.recordAttempt("198.51.100.9", "x@example.com");
        }
        assertThat(limiter.bucketCount()).isLessThanOrEqualTo(3);
        assertThat(limiter.isAllowed("198.51.100.9", "x@example.com"))
                .as("recently used bucket survives eviction").isFalse();
    }
}