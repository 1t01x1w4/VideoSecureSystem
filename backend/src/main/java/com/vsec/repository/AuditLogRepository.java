package com.vsec.repository;

import com.vsec.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    Page<AuditLog> findByUuidAndAction(String uuid, String action, Pageable pageable);

    Page<AuditLog> findByUuid(String uuid, Pageable pageable);

    Page<AuditLog> findByAction(String action, Pageable pageable);
}
