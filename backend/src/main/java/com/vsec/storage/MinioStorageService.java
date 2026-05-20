package com.vsec.storage;

import io.minio.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Path;

public class MinioStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(MinioStorageService.class);

    private final MinioClient client;
    private final String bucket;

    public MinioStorageService(MinioClient client, String bucket) {
        this.client = client;
        this.bucket = bucket;
        ensureBucket();
    }

    private void ensureBucket() {
        try {
            boolean found = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!found) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("MinIO bucket created: {}", bucket);
            }
        } catch (Exception e) {
            throw new RuntimeException("MinIO bucket 初始化失败: " + bucket, e);
        }
    }

    @Override
    public void store(String key, Path localFile) throws IOException {
        try {
            client.uploadObject(UploadObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .filename(localFile.toString())
                    .build());
        } catch (Exception e) {
            throw new IOException("MinIO upload failed: " + key, e);
        }
    }

    @Override
    public InputStream openStream(String key) throws IOException {
        try {
            return client.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .build());
        } catch (Exception e) {
            throw new IOException("MinIO getObject failed: " + key, e);
        }
    }

    @Override
    public InputStream openStream(String key, long offset, long length) throws IOException {
        try {
            return client.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .offset(offset)
                    .length(length)
                    .build());
        } catch (Exception e) {
            throw new IOException("MinIO range getObject failed: " + key, e);
        }
    }

    @Override
    public void delete(String key) throws IOException {
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .build());
        } catch (Exception e) {
            throw new IOException("MinIO delete failed: " + key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            client.statObject(StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public long size(String key) throws IOException {
        try {
            return client.statObject(StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .build()).size();
        } catch (Exception e) {
            throw new IOException("MinIO stat failed: " + key, e);
        }
    }
}
