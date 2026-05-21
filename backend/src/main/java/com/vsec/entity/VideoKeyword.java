package com.vsec.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "video_keywords",
       uniqueConstraints = @UniqueConstraint(columnNames = {"video_id", "keyword_hash"}),
       indexes = @Index(columnList = "keyword_hash"))
public class VideoKeyword {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "video_id", length = 36, nullable = false)
    private String videoId;

    @Column(name = "keyword_hash", nullable = false, columnDefinition = "BYTEA")
    private byte[] keywordHash;

    public VideoKeyword() {}

    public VideoKeyword(String videoId, byte[] keywordHash) {
        this.videoId = videoId;
        this.keywordHash = keywordHash;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getVideoId() { return videoId; }
    public void setVideoId(String videoId) { this.videoId = videoId; }
    public byte[] getKeywordHash() { return keywordHash; }
    public void setKeywordHash(byte[] keywordHash) { this.keywordHash = keywordHash; }
}
