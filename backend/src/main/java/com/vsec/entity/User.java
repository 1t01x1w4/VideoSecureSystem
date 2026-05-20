package com.vsec.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
public class User {

    @Id
    @Column(name = "uuid", length = 36, nullable = false)
    private String uuid;

    @Column(name = "email", length = 255, nullable = false, unique = true)
    private String email;

    @Column(name = "salt", nullable = false)
    private byte[] salt;

    @Column(name = "auth_key", nullable = false)
    private byte[] authKey;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "status", length = 20, nullable = false)
    private String status = "active";

    public User() {}

    public User(String uuid, String email, byte[] salt, byte[] authKey) {
        this.uuid = uuid;
        this.email = email;
        this.salt = salt;
        this.authKey = authKey;
        this.createdAt = LocalDateTime.now();
        this.status = "active";
    }

    public String getUuid() { return uuid; }
    public void setUuid(String uuid) { this.uuid = uuid; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public byte[] getSalt() { return salt; }
    public void setSalt(byte[] salt) { this.salt = salt; }
    public byte[] getAuthKey() { return authKey; }
    public void setAuthKey(byte[] authKey) { this.authKey = authKey; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
