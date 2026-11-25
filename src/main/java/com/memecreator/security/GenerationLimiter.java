package com.memecreator.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * At most meme.web.max-sets-per-hour meme sets per signed-in user in any one-hour window, so one person can't use
 * up the AI provider's rate limits. In memory: resets on restart, one instance only.
 */
@Component
public class GenerationLimiter {

    static final Duration WINDOW = Duration.ofHours(1);

    private final int max;
    private final Clock clock;
    private final Map<String, Deque<Instant>> uses = new ConcurrentHashMap<>();

    @Autowired // the app's constructor; the other one is for tests
    public GenerationLimiter(WebProperties props) {
        this(props.maxSetsPerHour(), Clock.systemUTC());
    }

    GenerationLimiter(int max, Clock clock) {
        this.max = max;
        this.clock = clock;
    }

    /** Thrown when the user has used up the hour; says when the next set is allowed. */
    public static final class LimitReachedException extends RuntimeException {
        private final Duration retryAfter;

        LimitReachedException(int max, Duration retryAfter) {
            super("You can make " + max + " meme sets per hour. Try again in "
                    + Math.max(1, (retryAfter.toSeconds() + 59) / 60) + " min.");
            this.retryAfter = retryAfter;
        }

        public Duration retryAfter() {
            return retryAfter;
        }
    }

    /** Records one use, or throws {@link LimitReachedException} without recording it. */
    public void acquire(String userId) {
        Instant now = clock.instant();
        Deque<Instant> q = uses.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && !q.peekFirst().isAfter(now.minus(WINDOW))) {
                q.pollFirst();
            }
            if (q.size() >= max) {
                throw new LimitReachedException(max, Duration.between(now, q.peekFirst().plus(WINDOW)));
            }
            q.addLast(now);
        }
    }

    /** Gives a use back, e.g. when the AI call failed. */
    public void release(String userId) {
        Deque<Instant> q = uses.get(userId);
        if (q != null) {
            synchronized (q) {
                q.pollLast();
            }
        }
    }
}
