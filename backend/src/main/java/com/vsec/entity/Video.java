package com.vsec.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "videos")
public class Video {

    @Id
    @Column(name = "video_id", length = 36, nullable = false)
    private String videoId;

    @Column(name = "uuid", length = 36, nullable = false)
    private String uuid;

    @Column(name = "title", length = 255)
    private String title;

    @Column(name = "original_filename", length = 255, nullable = false)
    private String originalFilename;

    @Column(name = "encrypted_dek", nullable = false)
    private byte[] encryptedDek;

    @Column(name = "ctr_iv", nullable = false)
    private byte[] ctrIv;

    @Column(name = "original_sm3", nullable = false)
    private byte[] originalSm3;

    @Column(name = "encrypted_sm3", nullable = false)
    private byte[] encryptedSm3;

    @Column(name = "file_path", length = 512, nullable = false)
    private String filePath;

    @Column(name = "mime_type", length = 50, nullable = false)
    private String mimeType = "video/mp4";

    @Column(name = "size", nullable = false)
    private long size;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public Video() {}

    public Video(String videoId, String uuid, String title, String originalFilename,
                 byte[] encryptedDek, byte[] ctrIv, byte[] originalSm3, byte[] encryptedSm3,
                 String filePath, String mimeType, long size) {
        this.videoId = videoId;
        this.uuid = uuid;
        this.title = title;
        this.originalFilename = originalFilename;
        this.encryptedDek = encryptedDek;
        this.ctrIv = ctrIv;
        this.originalSm3 = originalSm3;
        this.encryptedSm3 = encryptedSm3;
        this.filePath = filePath;
        this.mimeType = mimeType;
        this.size = size;
        this.createdAt = LocalDateTime.now();
    }

    public String getVideoId() { return videoId; }
    public void setVideoId(String videoId) { this.videoId = videoId; }
    public String getUuid() { return uuid; }
    public void setUuid(String uuid) { this.uuid = uuid; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }
    public byte[] getEncryptedDek() { return encryptedDek; }
    public void setEncryptedDek(byte[] encryptedDek) { this.encryptedDek = encryptedDek; }
    public byte[] getCtrIv() { return ctrIv; }
    public void setCtrIv(byte[] ctrIv) { this.ctrIv = ctrIv; }
    public byte[] getOriginalSm3() { return originalSm3; }
    public void setOriginalSm3(byte[] originalSm3) { this.originalSm3 = originalSm3; }
    public byte[] getEncryptedSm3() { return encryptedSm3; }
    public void setEncryptedSm3(byte[] encryptedSm3) { this.encryptedSm3 = encryptedSm3; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
