package com.collabcase.runtime.record;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

public interface RuntimeRecordRepository extends JpaRepository<RuntimeRecord,UUID> {
    List<RuntimeRecord> findByAppIdAndEntityTypeAndDeletedFalseOrderByUpdatedAtDesc(UUID appId,String entityType);
    Optional<RuntimeRecord> findByAppIdAndEntityTypeAndRecordId(UUID appId,String entityType,UUID recordId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RuntimeRecord> findLockedByAppIdAndEntityTypeAndRecordId(UUID appId,String entityType,UUID recordId);
    List<RuntimeRecord> findByAppIdAndSyncVersionGreaterThanOrderBySyncVersionAsc(UUID appId,long after);
}
