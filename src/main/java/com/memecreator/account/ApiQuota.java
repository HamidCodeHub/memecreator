package com.memecreator.account;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

/**
 * Daily limit on meme sets made through the API: counted from the database (survives restarts), plus the calls
 * still running, so parallel calls can't go over the limit.
 */
@Component
public class ApiQuota {

    private final MemeSetRepository memeSets;
    private final ApiProperties props;
    private final ZoneId zone;
    private final Clock clock;
    private final Map<Long, Integer> running = new HashMap<>();

    @Autowired
    public ApiQuota(MemeSetRepository memeSets, ApiProperties props) {
        this(memeSets, props, Clock.systemUTC());
    }

    ApiQuota(MemeSetRepository memeSets, ApiProperties props, Clock clock) {
        this.memeSets = memeSets;
        this.props = props;
        this.zone = ZoneId.of(props.quotaZone());
        this.clock = clock;
    }

    /** @param resetsAt when the count goes back to 0 (midnight in meme.api.quota-zone) */
    public record Usage(int limit, int used, Instant resetsAt) {
        public int remaining() {
            return Math.max(0, limit - used);
        }
    }

    public static final class QuotaExceededException extends RuntimeException {
        private final Usage usage;
        private final Duration retryAfter;

        public QuotaExceededException(Usage usage, Duration retryAfter) {
            super("You've made " + usage.limit() + " meme sets through the API today, the daily limit. "
                    + "It resets at midnight (" + usage.resetsAt() + ").");
            this.usage = usage;
            this.retryAfter = retryAfter;
        }

        public Usage usage() {
            return usage;
        }

        public Duration retryAfter() {
            return retryAfter;
        }
    }

    public Usage usage(long accountId, UserAccount.Plan plan) {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        Instant start = today.atStartOfDay(zone).toInstant();
        int used = (int) memeSets.countByAccountIdAndSourceAndCreatedAtGreaterThanEqual(accountId,
                MemeSet.Source.API, start);
        int limit = plan == UserAccount.Plan.PREMIUM ? props.premiumSetsPerDay() : props.freeSetsPerDay();
        return new Usage(limit, used, today.plusDays(1).atStartOfDay(zone).toInstant());
    }

    /** Takes one call from today's quota; call {@link #release} when the call is over (success or not). */
    public synchronized Usage acquire(long accountId, UserAccount.Plan plan) {
        Usage u = usage(accountId, plan);
        int inFlight = running.getOrDefault(accountId, 0);
        if (u.used() + inFlight >= u.limit()) {
            throw new QuotaExceededException(u, Duration.between(clock.instant(), u.resetsAt()));
        }
        running.merge(accountId, 1, Integer::sum);
        return new Usage(u.limit(), u.used() + inFlight + 1, u.resetsAt());
    }

    public synchronized void release(long accountId) {
        running.computeIfPresent(accountId, (k, n) -> n <= 1 ? null : n - 1);
    }
}
