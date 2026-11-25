package com.memecreator.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FavoriteRepository extends JpaRepository<Favorite, Long> {

    List<Favorite> findByAccountIdOrderByCreatedAtDescIdDesc(Long accountId);

    Optional<Favorite> findByAccountIdAndFolderAndFile(Long accountId, String folder, String file);

    Optional<Favorite> findByIdAndAccountId(Long id, Long accountId);

    long countByAccountId(Long accountId);

    List<Favorite> findByFolder(String folder);
}
