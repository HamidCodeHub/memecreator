package com.memecreator.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface MemeSetRepository extends JpaRepository<MemeSet, Long> {

    List<MemeSet> findByAccountId(Long accountId);

    boolean existsByAccountIdAndFolder(Long accountId, String folder);

    long countByAccountIdAndSourceAndCreatedAtGreaterThanEqual(Long accountId, MemeSet.Source source, Instant since);

    /** Usually one set; two accounts could in theory get the same folder name in the same second. */
    List<MemeSet> findByFolder(String folder);
}
