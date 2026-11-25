package com.memecreator.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserApiKeyRepository extends JpaRepository<UserApiKey, Long> {

    Optional<UserApiKey> findByKeyHash(String keyHash);

    List<UserApiKey> findByAccountIdOrderByCreatedAtDesc(Long accountId);

    Optional<UserApiKey> findByIdAndAccountId(Long id, Long accountId);

    long countByAccountId(Long accountId);
}
