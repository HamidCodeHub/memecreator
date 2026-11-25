package com.memecreator.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SharedMemeRepository extends JpaRepository<SharedMeme, String> {

    Optional<SharedMeme> findByAccountIdAndFolderAndFile(Long accountId, String folder, String file);

    List<SharedMeme> findByFolder(String folder);
}
