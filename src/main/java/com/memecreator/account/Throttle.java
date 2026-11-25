package com.memecreator.account;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Counts events per key in a sliding window: failed logins per email and per IP, sign-ups and emails per IP and
 * per address. In memory (resets on restart), which is enough to stop password guessing and email bombing.
 */
@Component
public class Throttle {

    /** Thrown when a key has used up its window. */
    public static final class TooManyAttemptsException extends RuntimeException {
        private final Duration retryAfter;

        TooManyAttemptsException(Duration retryAfter) {
            super("Too many attempts. Try again in " + Math.max(1, (retryAfter.toSeconds() + 59) / 60) + " min.");
            this.retryAfter = retryAfter;
        }

        public Duration retryAfter() {
            return retryAfter;
        }
    }

    private final Clock clock;
    private final Map<String, Deque<Instant>> events = new ConcurrentHashMap<>();

    public Throttle() {
        this(Clock.systemUTC());
    }

    Throttle(Clock clock) {
        this.clock = clock;
    }

    /** Throws when {@code key} already had {@code max} events within {@code window}; does not record one. */
    public void check(String key, int max, Duration window) {
        Instant now = clock.instant();
        Deque<Instant> q = events.get(key);
        if (q == null) {
            return;
        }
        synchronized (q) {
            prune(q, now, window);
            if (q.size() >= max) {
                throw new TooManyAttemptsException(Duration.between(now, q.peekFirst().plus(window)));
            }
        }
    }

    public void record(String key) {
        Deque<Instant> q = events.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(clock.instant());
            while (q.size() > 1000) {
                q.pollFirst();
            }
        }
    }

    /** check + record in one step. */
    public void acquire(String key, int max, Duration window) {
        check(key, max, window);
        record(key);
    }

    public void reset(String key) {
        events.remove(key);
    }

    private static void prune(Deque<Instant> q, Instant now, Duration window) {
        while (!q.isEmpty() && !q.peekFirst().isAfter(now.minus(window))) {
            q.pollFirst();
        }
    }
}
