package com.memecreator.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    Optional<UserAccount> findByEmail(String email);

    /** Email + password sign-ups whose address was never confirmed. Their links etc. go too (on delete cascade). */
    @Modifying
    @Query("delete from UserAccount a where a.emailVerified = false and a.passwordHash is not null "
            + "and a.createdAt < :before")
    int deleteUnconfirmedCreatedBefore(Instant before);
}
