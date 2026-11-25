package com.memecreator.account;

import com.memecreator.security.WebProperties;
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
 * Daily limit on prompts in the web app (a meme set from a topic or from a photo): meme.web.free-prompts-per-day
 * for FREE accounts, meme.web.premium-prompts-per-day for PREMIUM ones, 0 = no limit. Counted from the database
 * (survives restarts; failed sets are never recorded, so they don't count), plus the prompts still running, so
 * parallel requests can't go over the limit. The day resets at midnight in meme.api.quota-zone.
 */
@Component
public class PromptQuota {

    private final MemeSetRepository memeSets;
    private final WebProperties props;
    private final ZoneId zone;
    private final Clock clock;
    private final Map<Long, Integer> running = new HashMap<>();

    @Autowired
    public PromptQuota(MemeSetRepository memeSets, WebProperties props, ApiProperties api) {
        this(memeSets, props, ZoneId.of(api.quotaZone()), Clock.systemUTC());
    }

    PromptQuota(MemeSetRepository memeSets, WebProperties props, ZoneId zone, Clock clock) {
        this.memeSets = memeSets;
        this.props = props;
        this.zone = zone;
        this.clock = clock;
    }

    /**
     * @param limit    prompts per day for this plan; 0 = no limit
     * @param used     prompts made today (including the one just acquired, when returned by {@link #acquire})
     * @param resetsAt when the count goes back to 0
     */
    public record Usage(UserAccount.Plan plan, int limit, int used, Instant resetsAt) {
        public boolean unlimited() {
            return limit == 0;
        }

        /** @return prompts left today, or null when there is no limit */
        public Integer remaining() {
            return unlimited() ? null : Math.max(0, limit - used);
        }
    }

    /** Today's prompts are used up. */
    public static final class PromptLimitException extends RuntimeException {
        private final Usage usage;
        private final Duration retryAfter;

        PromptLimitException(Usage usage, Duration retryAfter) {
            super("You've used your " + usage.limit() + " prompts for today. They come back at midnight"
                    + (usage.plan() == UserAccount.Plan.FREE ? ", or go Premium for more." : "."));
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

    public int limitFor(UserAccount.Plan plan) {
        return plan == UserAccount.Plan.PREMIUM ? props.premiumPromptsPerDay() : props.freePromptsPerDay();
    }

    public Usage usage(long accountId, UserAccount.Plan plan) {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        Instant start = today.atStartOfDay(zone).toInstant();
        int used = (int) memeSets.countByAccountIdAndSourceAndCreatedAtGreaterThanEqual(accountId,
                MemeSet.Source.WEB, start);
        return new Usage(plan, limitFor(plan), used, today.plusDays(1).atStartOfDay(zone).toInstant());
    }

    /**
     * Takes one prompt from today's allowance, or throws {@link PromptLimitException}. Always call
     * {@link #release} when the prompt is over (success or not); a successful one is then counted from the
     * database.
     */
    public synchronized Usage acquire(long accountId, UserAccount.Plan plan) {
        Usage u = usage(accountId, plan);
        int inFlight = running.getOrDefault(accountId, 0);
        if (!u.unlimited() && u.used() + inFlight >= u.limit()) {
            throw new PromptLimitException(u, Duration.between(clock.instant(), u.resetsAt()));
        }
        running.merge(accountId, 1, Integer::sum);
        return new Usage(plan, u.limit(), u.used() + inFlight + 1, u.resetsAt());
    }

    public synchronized void release(long accountId) {
        running.computeIfPresent(accountId, (k, n) -> n <= 1 ? null : n - 1);
    }
}
