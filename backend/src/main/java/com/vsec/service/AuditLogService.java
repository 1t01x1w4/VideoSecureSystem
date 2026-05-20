package com.vsec.service;

import com.vsec.entity.AuditLog;
import com.vsec.repository.AuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private final AuditLogRepository repo;

    public AuditLogService(AuditLogRepository repo) {
        this.repo = repo;
    }

    public void record(String uuid, String action, String targetId,
                       HttpServletRequest request, String result, String detail) {
        try {
            AuditLog entry = new AuditLog(
                    uuid, action, targetId,
                    request.getRemoteAddr(),
                    request.getHeader("User-Agent"),
                    result, detail);
            repo.save(entry);
        } catch (Exception e) {
            log.error("审计日志写入失败: {}", e.getMessage());
        }
    }

    public Page<AuditLog> query(String uuid, String action, Pageable pageable) {
        if (uuid != null && !uuid.isBlank() && action != null && !action.isBlank()) {
            return repo.findByUuidAndAction(uuid, action, pageable);
        } else if (uuid != null && !uuid.isBlank()) {
            return repo.findByUuid(uuid, pageable);
        } else if (action != null && !action.isBlank()) {
            return repo.findByAction(action, pageable);
        }
        return repo.findAll(pageable);
    }
}
