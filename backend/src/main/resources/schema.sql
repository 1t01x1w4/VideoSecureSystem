-- ============================================
-- VSec-Storage 数据库初始化脚本
-- 手动执行：psql -U postgres -c "CREATE DATABASE vsec_storage;"
-- 然后：psql -U vsec -d vsec_storage -f schema.sql
-- ============================================

-- 用户表
CREATE TABLE IF NOT EXISTS users (
    uuid        VARCHAR(36)  PRIMARY KEY,
    email       VARCHAR(255) NOT NULL UNIQUE,
    salt        BYTEA        NOT NULL,
    auth_key    BYTEA        NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    status      VARCHAR(20)  NOT NULL DEFAULT 'active'
);

-- 视频元信息表
CREATE TABLE IF NOT EXISTS videos (
    video_id           VARCHAR(36)  PRIMARY KEY,
    uuid               VARCHAR(36)  NOT NULL REFERENCES users(uuid),
    title              VARCHAR(255),
    original_filename  VARCHAR(255) NOT NULL,
    encrypted_dek      BYTEA        NOT NULL,
    ctr_iv             BYTEA        NOT NULL,
    original_sm3       BYTEA        NOT NULL,
    encrypted_sm3      BYTEA        NOT NULL,
    file_path          VARCHAR(512) NOT NULL,
    mime_type          VARCHAR(50)  NOT NULL DEFAULT 'video/mp4',
    size               BIGINT       NOT NULL,
    created_at         TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- 审计日志表
CREATE TABLE IF NOT EXISTS audit_logs (
    id          BIGSERIAL    PRIMARY KEY,
    uuid        VARCHAR(36),
    action      VARCHAR(50)  NOT NULL,
    target_id   VARCHAR(36),
    ip          VARCHAR(45),
    user_agent  VARCHAR(512),
    result      VARCHAR(20)  NOT NULL,
    detail      TEXT,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- 索引
CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
CREATE INDEX IF NOT EXISTS idx_videos_uuid ON videos(uuid);
CREATE INDEX IF NOT EXISTS idx_audit_uuid ON audit_logs(uuid);
CREATE INDEX IF NOT EXISTS idx_audit_action ON audit_logs(action);
CREATE INDEX IF NOT EXISTS idx_audit_created ON audit_logs(created_at);
