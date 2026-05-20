package com.vsec.config;

import com.vsec.service.VideoService;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
public class SchedulingConfig {

    private final VideoService videoService;

    public SchedulingConfig(VideoService videoService) {
        this.videoService = videoService;
    }

    @Scheduled(fixedRate = 30 * 60 * 1000, initialDelay = 60 * 1000)
    public void cleanupStaleUploadDirs() {
        videoService.cleanupExpiredUploads();
    }
}
