package com.memecreator.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;

public interface EmailTokenRepository extends JpaRepository<EmailToken, String> {

    /** Makes every open token of this kind unusable, e.g. after a password reset. */
    @Modifying
    @Query("update EmailToken t set t.usedAt = :now where t.accountId = :accountId and t.purpose = :purpose "
            + "and t.usedAt is null")
    int useAll(Long accountId, EmailToken.Purpose purpose, Instant now);

    @Modifying
    @Query("delete from EmailToken t where t.expiresAt < :before")
    int deleteExpiredBefore(Instant before);
}
