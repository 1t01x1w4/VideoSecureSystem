package com.vsec.controller;

import com.vsec.entity.AuditLog;
import com.vsec.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public class AuditLogController {

    private final AuditLogService auditLogService;

    public AuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @GetMapping("/audit-logs")
    public ResponseEntity<Page<AuditLog>> getAuditLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String action,
            HttpServletRequest request) {
        String uuid = requireUuid(request);
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<AuditLog> result = auditLogService.query(uuid, action, pageable);
        return ResponseEntity.ok(result);
    }

    private String requireUuid(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) throw new RuntimeException("未登录");
        String uuid = (String) session.getAttribute("user_uuid");
        if (uuid == null) throw new RuntimeException("未登录");
        return uuid;
    }
}
