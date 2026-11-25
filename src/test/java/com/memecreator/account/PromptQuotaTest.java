package com.memecreator.account;

import com.memecreator.security.WebProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PromptQuotaTest {

    static final ZoneId ROME = ZoneId.of("Europe/Rome");
    // 23 Nov 2026, 21:40 in Rome
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-23T20:40:00Z"), ZoneId.of("UTC"));

    MemeSetRepository sets;
    PromptQuota quota;

    @BeforeEach
    void setUp() {
        sets = mock(MemeSetRepository.class);
        quota = new PromptQuota(sets, new WebProperties(6, 20, 10, 0, false, "/"), ROME, CLOCK);
    }

    void madeToday(long n) {
        // counted from midnight in Rome = 23:00 UTC the day before
        when(sets.countByAccountIdAndSourceAndCreatedAtGreaterThanEqual(eq(1L), eq(MemeSet.Source.WEB),
                eq(Instant.parse("2026-11-22T23:00:00Z")))).thenReturn(n);
    }

    @Test
    void freeAccountsGetTenPromptsADay() {
        madeToday(9);
        PromptQuota.Usage u = quota.acquire(1, UserAccount.Plan.FREE);
        assertThat(u.limit()).isEqualTo(10);
        assertThat(u.remaining()).isZero();
        assertThat(u.resetsAt()).isEqualTo(Instant.parse("2026-11-23T23:00:00Z"));
    }

    @Test
    void theEleventhPromptIsRefusedUntilMidnight() {
        madeToday(10);
        assertThatThrownBy(() -> quota.acquire(1, UserAccount.Plan.FREE))
                .isInstanceOf(PromptQuota.PromptLimitException.class)
                .hasMessageContaining("10 prompts")
                .hasMessageContaining("Premium")
                .satisfies(e -> assertThat(((PromptQuota.PromptLimitException) e).retryAfter().toMinutes())
                        .isEqualTo(140));
    }

    @Test
    void promptsStillRunningCountSoParallelRequestsCantGoOver() {
        madeToday(8);
        quota.acquire(1, UserAccount.Plan.FREE);
        quota.acquire(1, UserAccount.Plan.FREE);
        assertThatThrownBy(() -> quota.acquire(1, UserAccount.Plan.FREE))
                .isInstanceOf(PromptQuota.PromptLimitException.class);
        quota.release(1);
        quota.release(1);
        assertThat(quota.acquire(1, UserAccount.Plan.FREE).remaining()).isEqualTo(1);
    }

    @Test
    void premiumWithZeroHasNoDailyLimit() {
        when(sets.countByAccountIdAndSourceAndCreatedAtGreaterThanEqual(eq(1L), any(), any())).thenReturn(500L);
        PromptQuota.Usage u = quota.acquire(1, UserAccount.Plan.PREMIUM);
        assertThat(u.unlimited()).isTrue();
        assertThat(u.remaining()).isNull();
    }
}
