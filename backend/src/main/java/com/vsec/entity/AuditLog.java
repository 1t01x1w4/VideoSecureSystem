package com.vsec.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uuid", length = 36)
    private String uuid;

    @Column(name = "action", length = 50, nullable = false)
    private String action;

    @Column(name = "target_id", length = 36)
    private String targetId;

    @Column(name = "ip", length = 45)
    private String ip;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "result", length = 20, nullable = false)
    private String result;

    @Column(name = "detail", columnDefinition = "TEXT")
    private String detail;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public AuditLog() {}

    public AuditLog(String uuid, String action, String targetId, String ip,
                    String userAgent, String result, String detail) {
        this.uuid = uuid;
        this.action = action;
        this.targetId = targetId;
        this.ip = ip;
        this.userAgent = userAgent;
        this.result = result;
        this.detail = detail;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getUuid() { return uuid; }
    public String getAction() { return action; }
    public String getTargetId() { return targetId; }
    public String getIp() { return ip; }
    public String getUserAgent() { return userAgent; }
    public String getResult() { return result; }
    public String getDetail() { return detail; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
