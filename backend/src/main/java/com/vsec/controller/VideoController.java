package com.vsec.controller;

import com.vsec.entity.Video;
import com.vsec.crypto.CryptoUtil;
import com.vsec.exception.VsecException;
import com.vsec.service.AuditLogService;
import com.vsec.service.AuthService;
import com.vsec.service.VideoService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/videos")
public class VideoController {

    private final VideoService videoService;
    private final AuditLogService auditLogService;
    private final AuthService authService;

    public VideoController(VideoService videoService, AuditLogService auditLogService,
                           AuthService authService) {
        this.videoService = videoService;
        this.auditLogService = auditLogService;
        this.authService = authService;
    }

    private String requireUuid(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) throw new VsecException("未登录", org.springframework.http.HttpStatus.UNAUTHORIZED);
        String uuid = (String) session.getAttribute("user_uuid");
        if (uuid == null) throw new VsecException("未登录", org.springframework.http.HttpStatus.UNAUTHORIZED);
        return uuid;
    }

    private static String safeMimeType(Video v) {
        String mt = v.getMimeType();
        return (mt != null && mt.contains("/")) ? mt : "video/mp4";
    }

    // ==================== 上传 ====================

    public record InitUploadRequest(
            @NotBlank String filename,
            @Min(1) long totalSize,
            @Min(1) int totalChunks,
            String mime_type
    ) {}

    @PostMapping("/upload/init")
    public ResponseEntity<Map<String, Object>> initUpload(
            @RequestBody InitUploadRequest req,
            HttpServletRequest request) {
        String uuid = requireUuid(request);
        Map<String, Object> result = videoService.initUpload(
                uuid, req.filename, req.totalSize, req.totalChunks, req.mime_type);
        auditLogService.record(uuid, "UPLOAD_INIT", null, request, "SUCCESS", req.filename);
        return ResponseEntity.ok(result);
    }

    @PutMapping("/upload/{uploadId}/chunk/{chunkIndex}")
    public ResponseEntity<Map<String, String>> uploadChunk(
            @PathVariable String uploadId,
            @PathVariable int chunkIndex,
            @RequestBody byte[] chunkData,
            HttpServletRequest request) {
        String uuid = requireUuid(request);
        videoService.uploadChunk(uuid, uploadId, chunkIndex, chunkData);
        return ResponseEntity.ok(Map.of("message", "ok"));
    }

    public record CompleteUploadRequest(String title) {}

    @PostMapping("/upload/{uploadId}/complete")
    public ResponseEntity<Map<String, Object>> completeUpload(
            @PathVariable String uploadId,
            @RequestBody(required = false) CompleteUploadRequest req,
            HttpServletRequest request) {
        String uuid = requireUuid(request);
        byte[] mk = authService.getEncKey(request.getSession());
        if (mk == null) throw new VsecException("会话密钥已过期，请重新登录", org.springframework.http.HttpStatus.UNAUTHORIZED);
        Map<String, Object> result = videoService.completeUpload(
                uuid, uploadId, req != null ? req.title : null, mk);
        auditLogService.record(uuid, "UPLOAD", (String) result.get("video_id"),
                request, "SUCCESS", (String) result.get("video_id"));
        return ResponseEntity.ok(result);
    }

    // ==================== 视频管理 ====================

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listVideos(
            HttpServletRequest request,
            @RequestParam(required = false) String keyword) {
        String uuid = requireUuid(request);
        return ResponseEntity.ok(videoService.listVideos(uuid, keyword));
    }

    @DeleteMapping("/{videoId}")
    public ResponseEntity<Map<String, String>> deleteVideo(
            @PathVariable String videoId,
            HttpServletRequest request) {
        String uuid = requireUuid(request);
        videoService.deleteVideo(uuid, videoId);
        auditLogService.record(uuid, "DELETE", videoId, request, "SUCCESS", null);
        return ResponseEntity.ok(Map.of("message", "已删除"));
    }

    @PostMapping("/{videoId}/verify")
    public ResponseEntity<Map<String, Object>> verifyVideo(
            @PathVariable String videoId,
            HttpServletRequest request) {
        String uuid = requireUuid(request);
        boolean match = videoService.verifyVideo(uuid, videoId);
        auditLogService.record(uuid, "VERIFY", videoId, request,
                match ? "SUCCESS" : "FAIL", null);
        return ResponseEntity.ok(Map.of("match", match));
    }

    // ==================== 播放与下载 ====================

    @GetMapping("/{videoId}/stream")
    public ResponseEntity<StreamingResponseBody> streamVideo(
            @PathVariable String videoId,
            HttpServletRequest request) {
        String uuid = requireUuid(request);
        HttpSession session = request.getSession(false);
        byte[] mk = authService.getEncKey(session);
        if (mk == null) throw new VsecException("会话密钥已过期，请重新登录", org.springframework.http.HttpStatus.UNAUTHORIZED);

        Video video = videoService.getVideo(uuid, videoId);
        auditLogService.record(uuid, "VIEW", videoId, request, "SUCCESS",
                video.getOriginalFilename());
        String rangeHeader = request.getHeader("Range");

        // 无 Range 头 → 全文件流式解密
        if (rangeHeader == null || !rangeHeader.startsWith("bytes=")) {
            StreamingResponseBody body = out -> {
                try {
                    videoService.streamDecryptedVideo(video, mk, out);
                } finally {
                    CryptoUtil.wipe(mk);
                }
            };
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(safeMimeType(video)))
                    .contentLength(video.getSize())
                    .header("Accept-Ranges", "bytes")
                    .body(body);
        }

        // Range 请求 → 解密指定区间
        VideoService.RangeInfo range = VideoService.parseRange(rangeHeader, video.getSize());
        if (range == null) {
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header("Content-Range", "bytes */" + video.getSize())
                    .build();
        }

        long rangeLen = range.end() - range.start() + 1;
        if (rangeLen > Integer.MAX_VALUE) {
            // Range 过大，退化为全文件流式解密
            StreamingResponseBody fallback = out -> {
                try {
                    videoService.streamDecryptedVideo(video, mk, out);
                } finally {
                    CryptoUtil.wipe(mk);
                }
            };
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(safeMimeType(video)))
                    .contentLength(video.getSize())
                    .header("Accept-Ranges", "bytes")
                    .body(fallback);
        }
        StreamingResponseBody body = out -> {
            try {
                byte[] plain = videoService.decryptVideoRangeStreaming(
                        video, mk, range.start(), (int) rangeLen);
                out.write(plain);
            } finally {
                CryptoUtil.wipe(mk);
            }
        };

        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .contentType(MediaType.parseMediaType(safeMimeType(video)))
                .contentLength(rangeLen)
                .header("Content-Range",
                        "bytes " + range.start() + "-" + range.end() + "/" + video.getSize())
                .header("Accept-Ranges", "bytes")
                .body(body);
    }

    @GetMapping("/{videoId}/download")
    public ResponseEntity<StreamingResponseBody> downloadVideo(
            @PathVariable String videoId,
            HttpServletRequest request) {
        String uuid = requireUuid(request);
        HttpSession session = request.getSession(false);
        byte[] mk = authService.getEncKey(session);
        if (mk == null) throw new VsecException("会话密钥已过期，请重新登录", org.springframework.http.HttpStatus.UNAUTHORIZED);

        Video video = videoService.getVideo(uuid, videoId);
        auditLogService.record(uuid, "DOWNLOAD", videoId, request, "SUCCESS",
                video.getOriginalFilename());
        String filename = video.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            filename = videoId + ".mp4";
        }

        StreamingResponseBody body = out -> {
            try {
                videoService.streamDecryptedVideo(video, mk, out);
            } finally {
                CryptoUtil.wipe(mk);
            }
        };

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(safeMimeType(video)))
                .contentLength(video.getSize())
                .header("Content-Disposition",
                        "attachment; filename*=UTF-8''" + URLEncoder.encode(filename, StandardCharsets.UTF_8))
                .body(body);
    }
}
