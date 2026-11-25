package com.memecreator.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GenerationLimiterTest {

    /** A clock the test moves forward by hand. */
    static final class MovableClock extends Clock {
        Instant now = Instant.parse("2026-11-21T10:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void allowsMaxPerHourThenWaitsForTheOldestToExpire() {
        MovableClock clock = new MovableClock();
        GenerationLimiter limiter = new GenerationLimiter(2, clock);
        limiter.acquire("google:1");
        clock.now = clock.now.plus(Duration.ofMinutes(10));
        limiter.acquire("google:1");

        GenerationLimiter.LimitReachedException e =
                assertThrows(GenerationLimiter.LimitReachedException.class, () -> limiter.acquire("google:1"));
        assertEquals(Duration.ofMinutes(50), e.retryAfter());

        limiter.acquire("facebook:9"); // other users are not affected

        clock.now = clock.now.plus(Duration.ofMinutes(50));
        limiter.acquire("google:1"); // the first use has expired
    }

    @Test
    void releaseGivesAUseBack() {
        GenerationLimiter limiter = new GenerationLimiter(1, new MovableClock());
        limiter.acquire("u");
        limiter.release("u");
        limiter.acquire("u");
        assertThrows(GenerationLimiter.LimitReachedException.class, () -> limiter.acquire("u"));
    }
}
