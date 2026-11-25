package com.memecreator.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MemeRecordRepository extends JpaRepository<MemeRecord, Long> {

    Optional<MemeRecord> findByAccountIdAndFolderAndFile(Long accountId, String folder, String file);

    List<MemeRecord> findByFolder(String folder);
}
