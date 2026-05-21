package com.vsec.service;

import com.vsec.crypto.CryptoUtil;
import com.vsec.entity.Video;
import com.vsec.entity.VideoKeyword;
import com.vsec.exception.VsecException;
import com.vsec.repository.VideoKeywordRepository;
import com.vsec.repository.VideoRepository;
import com.vsec.storage.StorageService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class VideoService {

    private static final Logger log = LoggerFactory.getLogger(VideoService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final VideoRepository videoRepository;
    private final VideoKeywordRepository videoKeywordRepository;
    private final StringRedisTemplate redis;
    private final StorageService storageService;
    private final Path tempRoot;

    @Value("${app.security.chunk-size:5242880}")
    private int chunkSize;

    @Value("${app.security.server-secret:vsec-server-secret-dev}")
    private String serverSecret;

    public VideoService(VideoRepository videoRepository,
                        VideoKeywordRepository videoKeywordRepository,
                        StringRedisTemplate redis,
                        StorageService storageService,
                        @Value("${app.storage.temp-dir:/tmp/vsec-storage}") String tempDir) {
        this.videoRepository = videoRepository;
        this.videoKeywordRepository = videoKeywordRepository;
        this.redis = redis;
        this.storageService = storageService;
        this.tempRoot = Paths.get(tempDir);
        try {
            Files.createDirectories(tempRoot);
        } catch (IOException e) {
            throw new RuntimeException("无法创建临时目录: " + tempRoot, e);
        }
    }

    // ==================== 上传初始化 ====================

    public Map<String, Object> initUpload(String uuid, String filename, long totalSize,
                                          int totalChunks, String mimeType) {
        filename = sanitizeFilename(filename);
        String uploadId = UUID.randomUUID().toString();
        String videoId = UUID.randomUUID().toString();

        byte[] dek = CryptoUtil.generateDek();
        byte[] iv = CryptoUtil.generateIv();
        byte[] tempKey = CryptoUtil.generateDek();
        byte[] tempIv = CryptoUtil.generateIv();

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("videoId", videoId);
        state.put("uuid", uuid);
        state.put("filename", filename);
        state.put("totalSize", totalSize);
        state.put("totalChunks", totalChunks);
        state.put("chunkSize", chunkSize);
        state.put("dek", Base64.getEncoder().encodeToString(dek));
        state.put("iv", Base64.getEncoder().encodeToString(iv));
        state.put("tempKey", Base64.getEncoder().encodeToString(tempKey));
        state.put("tempIv", Base64.getEncoder().encodeToString(tempIv));
        state.put("mimeType", mimeType != null ? mimeType : "video/mp4");
        try {
            redis.opsForValue().set("upload:" + uploadId,
                    MAPPER.writeValueAsString(state), 2, TimeUnit.HOURS);
        } catch (Exception e) {
            throw new RuntimeException("初始化上传失败", e);
        }

        CryptoUtil.wipe(dek);
        CryptoUtil.wipe(iv);
        CryptoUtil.wipe(tempKey);
        CryptoUtil.wipe(tempIv);

        log.info("上传初始化: uploadId={}, videoId={}, filename={}, chunks={}",
                uploadId, videoId, filename, totalChunks);
        return Map.of("upload_id", uploadId, "video_id", videoId);
    }

    // ==================== 分块上传 ====================

    public void uploadChunk(String uuid, String uploadId, int chunkIndex, byte[] chunkData) {
        Map<String, Object> state = getUploadState(uploadId);
        if (state == null) throw new VsecException("上传会话不存在或已过期");
        if (!uuid.equals(state.get("uuid"))) throw new VsecException("无权操作此上传");

        int totalChunks = ((Number) state.get("totalChunks")).intValue();
        if (chunkIndex < 0 || chunkIndex >= totalChunks)
            throw new VsecException("分块索引无效: " + chunkIndex);

        // M2: 拒绝超大分块，防止内存耗尽
        if (chunkData.length > chunkSize)
            throw new VsecException("分块数据超限: " + chunkData.length + " > " + chunkSize);

        Path tempDir = tempRoot.resolve(uploadId);
        try {
            Files.createDirectories(tempDir);
            if (chunkIndex == 0 && !isValidMp4Header(chunkData)) {
                throw new VsecException("仅支持 MP4 视频文件，请检查文件格式");
            }
            // 立即用临时密钥加密分块，防止明文视频落盘
            byte[] tempKey = Base64.getDecoder().decode((String) state.get("tempKey"));
            byte[] tempIv = Base64.getDecoder().decode((String) state.get("tempIv"));
            byte[] encChunk = CryptoUtil.sm4CtrEncrypt(tempKey, tempIv, chunkData);
            Files.write(tempDir.resolve("chunk_" + chunkIndex + ".enc"), encChunk);
        } catch (IOException e) {
            throw new RuntimeException("写入分块失败", e);
        }

        // M1: 使用 Redis SET 原子操作，消除 JSON ArrayList 读-改-写竞态
        String chunksKey = "upload:chunks:" + uploadId;
        redis.opsForSet().add(chunksKey, String.valueOf(chunkIndex));
        redis.expire(chunksKey, 2, TimeUnit.HOURS);
        long receivedCount = redis.opsForSet().size(chunksKey);

        try {
            redis.opsForValue().set("upload:" + uploadId,
                    MAPPER.writeValueAsString(state), 2, TimeUnit.HOURS);
        } catch (Exception e) {
            throw new RuntimeException("更新上传状态失败", e);
        }

        log.debug("分块上传: uploadId={}, chunk={}, received={}/{}",
                uploadId, chunkIndex, receivedCount, totalChunks);
    }

    // ==================== 完成上传 ====================

    @SuppressWarnings("unchecked")
    @Transactional
    public Map<String, Object> completeUpload(String uuid, String uploadId, String title,
                                               byte[] mk) {
        Map<String, Object> state = getUploadState(uploadId);
        if (state == null) throw new VsecException("上传会话不存在或已过期");
        if (!uuid.equals(state.get("uuid"))) throw new VsecException("无权操作此上传");

        String chunksKey = "upload:chunks:" + uploadId;
        int totalChunks = ((Number) state.get("totalChunks")).intValue();
        Long receivedCount = redis.opsForSet().size(chunksKey);
        if (receivedCount == null || receivedCount != totalChunks)
            throw new VsecException("分块未全部上传: " + receivedCount + "/" + totalChunks);

        byte[] dek = Base64.getDecoder().decode((String) state.get("dek"));
        byte[] ctrIv = Base64.getDecoder().decode((String) state.get("iv"));
        byte[] tempKey = Base64.getDecoder().decode((String) state.get("tempKey"));
        byte[] tempIv = Base64.getDecoder().decode((String) state.get("tempIv"));
        String videoId = (String) state.get("videoId");
        String filename = (String) state.get("filename");
        String mimeType = (String) state.get("mimeType");
        long totalSize = ((Number) state.get("totalSize")).longValue();

        Path tempDir = tempRoot.resolve(uploadId);
        String fileKey = uuid + "/" + videoId + ".enc";
        Path tempEncFile = tempRoot.resolve(uuid).resolve(videoId + ".enc");

        try {
            Files.createDirectories(tempEncFile.getParent());

            var sm3Original = new org.bouncycastle.crypto.digests.SM3Digest();
            var sm3Encrypted = new org.bouncycastle.crypto.digests.SM3Digest();
            int cs = ((Number) state.get("chunkSize")).intValue();

            try (OutputStream out = Files.newOutputStream(tempEncFile)) {
                for (int i = 0; i < totalChunks; i++) {
                    Path chunkPath = tempDir.resolve("chunk_" + i + ".enc");
                    byte[] encChunk = Files.readAllBytes(chunkPath);
                    // 先用临时密钥解密分块，还原明文
                    byte[] plainChunk = CryptoUtil.sm4CtrDecrypt(tempKey, tempIv, encChunk);

                    sm3Original.update(plainChunk, 0, plainChunk.length);

                    long blockOffset = (long) i * cs / 16;
                    byte[] chunkIv = CryptoUtil.adjustCounter(ctrIv, blockOffset);
                    byte[] encryptedChunk = CryptoUtil.sm4CtrEncrypt(dek, chunkIv, plainChunk);

                    sm3Encrypted.update(encryptedChunk, 0, encryptedChunk.length);
                    out.write(encryptedChunk);
                    CryptoUtil.wipe(plainChunk);
                }
            }

            byte[] origHash = new byte[sm3Original.getDigestSize()];
            sm3Original.doFinal(origHash, 0);
            byte[] encHash = new byte[sm3Encrypted.getDigestSize()];
            sm3Encrypted.doFinal(encHash, 0);

            // 信封加密：用 MK 加密 DEK（ctrIv 作为 CBC IV，因密钥不同，IV 复用安全）
            byte[] encryptedDek = CryptoUtil.encryptDek(mk, dek, ctrIv);

            // 上传密文到存储后端
            storageService.store(fileKey, tempEncFile);

            // 回验 MinIO 中文件的 SM3，防止传输损坏
            byte[] storedSm3;
            try (InputStream verifyStream = storageService.openStream(fileKey)) {
                storedSm3 = CryptoUtil.sm3Stream(verifyStream);
            }
            if (!CryptoUtil.constantTimeEquals(storedSm3, encHash)) {
                try { storageService.delete(fileKey); } catch (IOException ignored) {}
                throw new VsecException("MinIO 存储校验失败，上传可能损坏，请重试");
            }

            Video video = new Video(videoId, uuid,
                    title != null ? title : filename, filename,
                    encryptedDek, ctrIv, origHash, encHash,
                    fileKey, mimeType, totalSize);
            videoRepository.saveAndFlush(video);

            // 构建加密搜索索引（独立于视频保存，失败不回滚）
            try {
                Set<String> tokens = new LinkedHashSet<>();
                tokens.addAll(extractBigrams(normalize(title != null ? title : filename)));
                tokens.addAll(extractBigrams(normalize(filename)));
                buildKeywordIndex(videoId, tokens);
            } catch (Exception e) {
                log.warn("搜索索引构建失败: videoId={}, error={}", videoId, e.getMessage());
            }

            Files.deleteIfExists(tempEncFile);

            log.info("上传完成: videoId={}, filename={}, size={}",
                    videoId, filename, totalSize);

            return Map.of("video_id", videoId, "message", "上传完成");

        } catch (IOException e) {
            try { Files.deleteIfExists(tempEncFile); } catch (IOException ignored) {}
            throw new RuntimeException("完成上传失败: " + e.getMessage(), e);
        } finally {
            CryptoUtil.wipe(dek);
            CryptoUtil.wipe(mk);
            CryptoUtil.wipe(tempKey);
            CryptoUtil.wipe(tempIv);
            redis.delete("upload:" + uploadId);
            redis.delete("upload:chunks:" + uploadId);
            deleteDirectory(tempDir);
        }
    }

    // ==================== 视频列表 ====================

    public List<Map<String, Object>> listVideos(String uuid, String keyword) {
        List<Video> videos;
        if (keyword != null && !keyword.isBlank()) {
            Set<String> videoIds = searchByKeyword(keyword);
            if (videoIds.isEmpty()) return Collections.emptyList();
            videos = videoRepository.findByUuidAndVideoIdInOrderByCreatedAtDesc(uuid, videoIds);
        } else {
            videos = videoRepository.findByUuidOrderByCreatedAtDesc(uuid);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Video v : videos) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("video_id", v.getVideoId());
            item.put("title", v.getTitle());
            item.put("original_filename", v.getOriginalFilename());
            item.put("size", v.getSize());
            item.put("mime_type", v.getMimeType());
            item.put("created_at", v.getCreatedAt().toString());
            result.add(item);
        }
        return result;
    }

    // ==================== 视频删除 ====================

    @Transactional
    public void deleteVideo(String uuid, String videoId) {
        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new VsecException("视频不存在"));
        if (!video.getUuid().equals(uuid))
            throw new VsecException("无权操作此视频");

        try {
            storageService.delete(video.getFilePath());
        } catch (IOException e) {
            log.warn("删除加密文件失败: {}", e.getMessage());
        }

        videoKeywordRepository.deleteByVideoId(videoId);
        videoRepository.delete(video);
        log.info("视频已删除: videoId={}", videoId);
    }

    // ==================== 完整性校验 ====================

    public boolean verifyVideo(String uuid, String videoId) {
        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new VsecException("视频不存在"));
        if (!video.getUuid().equals(uuid))
            throw new VsecException("无权操作此视频");

        if (!storageService.exists(video.getFilePath())) return false;

        try (InputStream is = storageService.openStream(video.getFilePath())) {
            byte[] actualSm3 = CryptoUtil.sm3Stream(is);
            return CryptoUtil.constantTimeEquals(actualSm3, video.getEncryptedSm3());
        } catch (IOException e) {
            log.error("校验读取失败: {}", e.getMessage());
            return false;
        }
    }

    // ==================== 视频播放/下载 ====================

    public Video getVideo(String uuid, String videoId) {
        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new VsecException("视频不存在"));
        if (!video.getUuid().equals(uuid))
            throw new VsecException("无权操作此视频");
        return video;
    }

    /**
     * 流式解密整个视频到 OutputStream（用于全文件播放和下载）。
     * 以 1MB 块为单位读取密文 → 解密 → 写入，不会将整个文件加载到内存。
     */
    public void streamDecryptedVideo(Video video, byte[] mk, OutputStream out) throws IOException {
        if (!storageService.exists(video.getFilePath())) throw new VsecException("视频文件不存在");

        byte[] dek = CryptoUtil.decryptDek(mk, video.getEncryptedDek(), video.getCtrIv());
        try (InputStream is = storageService.openStream(video.getFilePath())) {
            long offset = 0;
            int bufSize = 1024 * 1024; // 1MB，恰好是 16 的整数倍
            byte[] encBuf = new byte[bufSize];
            while (offset < video.getSize()) {
                int len = (int) Math.min(bufSize, video.getSize() - offset);
                int read = is.readNBytes(encBuf, 0, len);
                if (read <= 0) break;
                long blockOffset = offset / 16;
                byte[] chunkIv = CryptoUtil.adjustCounter(video.getCtrIv(), blockOffset);
                byte[] plain = CryptoUtil.sm4CtrDecrypt(dek, chunkIv,
                        Arrays.copyOf(encBuf, read));
                out.write(plain);
                offset += read;
            }
        } finally {
            CryptoUtil.wipe(dek);
        }
    }

    /**
     * 按字节区间解密，只读取所需密文到内存（支持 Range 请求的高效随机访问）。
     */
    public byte[] decryptVideoRangeStreaming(Video video, byte[] mk,
                                              long offset, int length) throws IOException {
        if (!storageService.exists(video.getFilePath())) throw new VsecException("视频文件不存在");

        long fileSize = storageService.size(video.getFilePath());
        long blockOffset = offset / 16;
        int skipBytes = (int) (offset % 16);
        long alignedStart = blockOffset * 16;

        int readLen = length + skipBytes;
        long remaining = fileSize - alignedStart;
        if (remaining <= 0) return new byte[0];
        if (readLen > remaining) readLen = (int) remaining;

        byte[] encChunk;
        try (InputStream is = storageService.openStream(video.getFilePath(), alignedStart, readLen)) {
            encChunk = is.readAllBytes();
        }

        byte[] dek = CryptoUtil.decryptDek(mk, video.getEncryptedDek(), video.getCtrIv());
        try {
            return CryptoUtil.sm4CtrDecryptAligned(dek, video.getCtrIv(),
                    encChunk, blockOffset, skipBytes, readLen - skipBytes);
        } finally {
            CryptoUtil.wipe(dek);
        }
    }

    /** HTTP Range 头解析结果。start/end 均为 inclusive，符合 RFC 7233。 */
    public record RangeInfo(long start, long end) {}

    /**
     * 解析 HTTP Range 请求头，返回 [start, end] inclusive 区间。
     * 支持: bytes=start-end, bytes=start-, bytes=-suffix
     * 返回 null 表示无法满足的 Range。
     */
    public static RangeInfo parseRange(String rangeHeader, long fileSize) {
        if (rangeHeader == null || !rangeHeader.startsWith("bytes=")) return null;
        String spec = rangeHeader.substring(6).trim();
        try {
            if (spec.startsWith("-")) {
                long suffix = Long.parseUnsignedLong(spec.substring(1));
                if (suffix == 0) return null;
                long start = Math.max(0, fileSize - suffix);
                return new RangeInfo(start, fileSize - 1);
            }
            int dash = spec.indexOf('-');
            long start = Long.parseUnsignedLong(spec.substring(0, dash));
            if (spec.charAt(spec.length() - 1) == '-') {
                if (start >= fileSize) return null;
                return new RangeInfo(start, fileSize - 1);
            } else {
                long end = Long.parseUnsignedLong(spec.substring(dash + 1));
                if (start > end || start >= fileSize) return null;
                return new RangeInfo(start, Math.min(end, fileSize - 1));
            }
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ==================== 加密搜索索引 ====================

    private void buildKeywordIndex(String videoId, Set<String> tokens) {
        if (tokens.isEmpty()) return;
        byte[] keyBytes = serverSecret.getBytes(StandardCharsets.UTF_8);
        List<VideoKeyword> batch = new ArrayList<>(tokens.size());
        for (String token : tokens) {
            byte[] hash = CryptoUtil.hmacSM3(keyBytes, token.getBytes(StandardCharsets.UTF_8));
            batch.add(new VideoKeyword(videoId, hash));
        }
        try {
            videoKeywordRepository.saveAll(batch);
        } catch (DataIntegrityViolationException e) {
            log.debug("关键词索引已存在（幂等忽略）: videoId={}", videoId);
        }
    }

    private Set<String> searchByKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) return Collections.emptySet();
        Set<String> tokens = extractBigrams(normalize(keyword.trim()));
        byte[] keyBytes = serverSecret.getBytes(StandardCharsets.UTF_8);
        Set<String> videoIds = new LinkedHashSet<>();
        for (String token : tokens) {
            byte[] hash = CryptoUtil.hmacSM3(keyBytes, token.getBytes(StandardCharsets.UTF_8));
            List<VideoKeyword> matches = videoKeywordRepository.findByKeywordHash(hash);
            for (VideoKeyword m : matches) {
                videoIds.add(m.getVideoId());
            }
        }
        return videoIds;
    }

    private static String normalize(String text) {
        if (text == null || text.isEmpty()) return text;
        return Normalizer.normalize(text, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    static Set<String> extractBigrams(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        if (text == null || text.length() < 2) {
            if (text != null && !text.isBlank()) tokens.add(text);
            return tokens;
        }
        // bigram: 每连续2字符
        for (int i = 0; i < text.length() - 1; i++) {
            tokens.add(text.substring(i, i + 2));
        }
        // 文本额外按空格/标点切完整词（Unicode 感知）
        for (String word : text.split("[^\\p{L}\\p{N}]+")) {
            if (!word.isBlank() && word.length() >= 2) {
                tokens.add(word);
            }
        }
        return tokens;
    }

    // ==================== 内部方法 ====================

    private static final String[] MP4_BRANDS = {
        "mp42", "isom", "avc1", "mp41", "iso2", "MSNV",
        "3gp4", "3gp5", "M4A ", "M4B ", "M4P ", "M4V ",
        "qt  ", "MSNV",
    };

    private boolean isValidMp4Header(byte[] data) {
        if (data == null || data.length < 12) return false;
        if (data[4] != 'f' || data[5] != 't' || data[6] != 'y' || data[7] != 'p')
            return false;
        String brand = new String(data, 8, 4, StandardCharsets.US_ASCII);
        for (String b : MP4_BRANDS) {
            if (brand.equals(b)) return true;
        }
        return false;
    }

    private Map<String, Object> getUploadState(String uploadId) {
        String json = redis.opsForValue().get("upload:" + uploadId);
        if (json == null) return null;
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    private void deleteDirectory(Path dir) {
        try {
            if (Files.exists(dir)) {
                try (var walk = Files.walk(dir)) {
                    walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
                }
            }
        } catch (IOException ignored) {}
    }

    // L2: 文件名消毒 —— 移除路径遍历/控制字符/Windows保留名
    private static final java.util.Set<String> WIN_RESERVED = java.util.Set.of(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    );

    private String sanitizeFilename(String name) {
        if (name == null || name.isBlank()) return "video.mp4";
        // 移除路径分隔符、空字节、控制字符
        String cleaned = name.replaceAll("[/\\\\\\x00-\\x1f\\x7f]", "_");
        // 截断到 255 字符
        if (cleaned.length() > 255) {
            cleaned = cleaned.substring(0, 255);
        }
        // 处理 Windows 保留名（不含扩展名）
        int dot = cleaned.lastIndexOf('.');
        String base = dot > 0 ? cleaned.substring(0, dot) : cleaned;
        if (WIN_RESERVED.contains(base.toUpperCase())) {
            cleaned = "_" + cleaned;
        }
        return cleaned.isEmpty() ? "video.mp4" : cleaned;
    }

    // M6: 定时清理放弃的上传临时目录（超过2小时未修改）
    public void cleanupExpiredUploads() {
        try (var dirs = Files.newDirectoryStream(tempRoot,
                p -> Files.isDirectory(p) && !p.getFileName().toString().contains("."))) {
            long now = System.currentTimeMillis();
            for (Path dir : dirs) {
                try {
                    long lastMod = Files.getLastModifiedTime(dir).toMillis();
                    if (now - lastMod > TimeUnit.HOURS.toMillis(2)) {
                        deleteDirectory(dir);
                        log.info("已清理过期上传目录: {}", dir.getFileName());
                    }
                } catch (IOException e) {
                    log.debug("清理目录检查失败: {}", dir.getFileName());
                }
            }
        } catch (IOException e) {
            log.warn("扫描临时目录失败: {}", e.getMessage());
        }
    }

    // 启动时自动为缺少索引的旧视频构建关键词索引
    @EventListener(ApplicationReadyEvent.class)
    public void migrateExistingVideoKeywords() {
        try {
            int built = 0;
            int page = 0;
            List<Video> pageVideos;
            do {
                pageVideos = videoRepository.findAll(PageRequest.of(page++, 500)).getContent();
                for (Video v : pageVideos) {
                    try {
                        if (videoKeywordRepository.countByVideoId(v.getVideoId()) > 0) continue;
                        Set<String> tokens = new LinkedHashSet<>();
                        String title = v.getTitle() != null
                                ? v.getTitle() : Objects.toString(v.getOriginalFilename(), "");
                        tokens.addAll(extractBigrams(normalize(title)));
                        tokens.addAll(extractBigrams(normalize(
                                Objects.toString(v.getOriginalFilename(), ""))));
                        buildKeywordIndex(v.getVideoId(), tokens);
                        built++;
                    } catch (Exception e) {
                        log.warn("旧视频索引构建失败: videoId={}, error={}",
                                v.getVideoId(), e.getMessage());
                    }
                }
            } while (!pageVideos.isEmpty());
            if (built > 0) log.info("已为 {} 个旧视频构建搜索索引", built);
        } catch (Exception e) {
            log.warn("旧视频索引迁移未完成: {}", e.getMessage());
        }
    }
}
