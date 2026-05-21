# VSec-Storage 系统使用说明书

## 一、系统概述

VSec-Storage 是一套视频大数据安全存储系统，采用**一视频一密钥**信封加密架构，使用国密 SM4-CTR 流式加解密、SM3 完整性校验，支持大文件分块上传下载和在线流式解密播放。

**核心能力：**
- 邮箱验证码 + 口令双因子登录（Argon2id + HKDF 密钥派生）
- 视频 SM4-CTR 分块加密上传，流式解密播放/下载，不临时落盘
- SM3 完整性校验，支持篡改检测
- 审计日志（注册/登录/上传/查看/下载/删除）
- 双存储后端：本地文件系统 / MinIO 对象存储，配置即切换

---

## 二、Docker 部署（推荐）

无需手动安装 JDK、Maven、PostgreSQL、Redis、MinIO 等依赖。

### 2.1 快速启动

```bash
git clone https://github.com/1t01x1w4/VideoSecureSystem.git
cd VideoSecureSystem

# 配置环境变量（可选，开发环境直接用默认值）
cp .env.example .env

# 启动所有服务
docker compose up -d
```

### 2.2 访问入口

| 服务 | 地址 |
|------|------|
| Web 前端 | http://localhost:8080 |
| MinIO 控制台 | http://localhost:9001 |

### 2.3 服务架构

```
frontend (nginx:80)         → http://localhost:8080
  └─ /api/ → backend:8443

backend (Spring Boot :8443) → https://localhost:8443
  ├─ postgres:5432          元数据/审计日志
  ├─ redis:6379             会话/缓存
  └─ minio:9000             加密视频存储
```

### 2.4 常用命令

```bash
docker compose ps          # 查看服务状态
docker compose logs -f     # 跟踪日志
docker compose down        # 停止并移除容器
docker compose down -v     # 同时删除数据卷（重置数据库和文件）
```

---

## 三、运行环境（手动部署）

| 组件 | 版本 | 用途 |
|------|------|------|
| JDK | 21 | Java 运行环境 |
| Maven | 3.9+ | 项目构建 |
| PostgreSQL | 16 | 元数据、审计日志 |
| Redis | 5.0+ | 会话管理、频率限制 |
| MinIO（可选） | 最新版 | 对象存储后端，仅 `minio` 模式需要 |

> **安全提醒：** 以下凭据仅适用于本地开发。部署到服务器前请通过 `.env` 文件修改所有密码。邮箱凭据为必改项（否则无法发送验证码）。

当前开发环境各服务端口和凭证：

| 服务 | 端口 | 账号 | 密码 |
|------|------|------|------|
| 后端 API | 8443 (HTTPS) | — | — |
| PostgreSQL | 5432 | `vsec` | `vsec123` |
| Redis | 6379 | — | `vsec123` |
| MinIO API | 9000 | `minioadmin` | `minioadmin` |
| MinIO 控制台 | 9001 | `minioadmin` | `minioadmin` |

---

## 四、启动依赖服务

### 4.1 启动 PostgreSQL

```bash
# 方式一：pg_ctl 启动
pg_ctl start -D /path/to/pgsql/data -l /path/to/pgsql/data/logfile.log

# 方式二：若安装为 Windows 服务
net start postgresql-x64-16
```

验证：
```bash
psql -U vsec -d vsec_storage -c "SELECT 1"
```

### 4.2 启动 Redis

```bash
redis-server /path/to/redis.conf
```

验证：
```bash
redis-cli -a vsec123 ping   # 返回 PONG
```

**安全提醒**：生产环境必须为 Redis 设置密码，并关闭 RDB/AOF 持久化（见第十节）。

### 4.3 启动 MinIO（仅 MinIO 存储模式需要）

#### 4.3.1 下载 MinIO

从 MinIO 官网下载对应平台的二进制文件：

```bash
# Linux/macOS
wget https://dl.min.io/server/minio/release/linux-amd64/minio
chmod +x minio

# Windows (PowerShell)
# 在浏览器中打开 https://dl.min.io/server/minio/release/windows-amd64/minio.exe
```

#### 4.3.2 启动 MinIO 服务

```bash
mkdir -p /path/to/minio/data
minio server /path/to/minio/data --console-address :9001
```

启动后输出示例：
```
API: http://127.0.0.1:9000
WebUI: http://127.0.0.1:9001
```

- **API 地址**：`http://localhost:9000`（后端连接用）
- **控制台地址**：`http://localhost:9001`（浏览器打开查看 Bucket 和对象）

默认账号密码均为 `minioadmin`。在生产环境中应通过环境变量修改：
```bash
export MINIO_ROOT_USER=your_admin
export MINIO_ROOT_PASSWORD=your_password
```

#### 4.3.3 停止 MinIO

在 MinIO 终端按 `Ctrl+C` 即可停止。

---

## 五、应用配置

核心配置文件：`backend/src/main/resources/application.yml`

### 5.1 存储配置

```yaml
app:
  minio:
    endpoint: http://localhost:9000
    access-key: minioadmin
    secret-key: minioadmin
    bucket: vsec-videos
  storage:
    temp-dir: /tmp/vsec-storage  # 上传临时分片目录
```

加密视频存储于 MinIO 的 `vsec-videos` Bucket，上传过程中的临时分片使用本地 `temp-dir`，完成后自动清理。

### 5.2 其他关键配置

```yaml
# 邮件（开发模式：验证码输出到控制台）
spring:
  mail:
    dev-mode: true    # 生产环境设为 false 并配置 SMTP

# 文件上传限制
spring.servlet.multipart:
  max-file-size: 5120MB

# 安全参数
app:
  security:
    argon2:
      memory-kb: 65536    # Argon2id 内存开销 64MB
      iterations: 4
      parallelism: 2
    login:
      max-attempts: 5     # 连续失败锁定阈值
      lock-minutes: 15    # 锁定时长
    code:
      ttl-minutes: 5      # 验证码有效期
      length: 6           # 验证码位数
    chunk-size: 5242880   # 分块大小 5MB
```

---

## 六、启动后端

### 6.1 编译

```bash
cd backend
mvn clean compile
```

### 6.2 运行

```bash
mvn spring-boot:run
```

首次启动时 Hibernate 会自动建表（`ddl-auto: update`）。看到以下日志表示启动成功：

```
Started VSecApplication in X.XX seconds
Tomcat started on port 8080 (http)
```

MinIO 模式下还会看到：
```
MinIO bucket created: vsec-videos
```

---

## 七、API 使用指南

所有 API 前缀为 `/api`，返回 Content-Type 为 `application/json`。除认证接口外均需携带会话 Cookie。

> **HTTPS 说明**：后端使用自签名证书，curl 需加 `-k` 标志跳过证书验证。浏览器首次访问时点击"高级 → 继续访问"。

### 7.1 用户注册

```bash
# 步骤 1：获取验证码（验证码输出到后端控制台）
curl -X POST https://localhost:8443/api/auth/code \
  -H "Content-Type: application/json" \
  -d '{"email":"user@example.com","purpose":"register"}'

# 步骤 2：查看控制台获取验证码（如：242924），然后注册
curl -X POST https://localhost:8443/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"user@example.com","password":"MyPass123","code":"242924"}'
# → {"uuid":"xxx","email":"user@example.com"}
```

### 7.2 用户登录

```bash
# 1. 获取登录验证码
curl -X POST https://localhost:8443/api/auth/code \
  -H "Content-Type: application/json" \
  -d '{"email":"user@example.com","purpose":"login"}'

# 2. 登录（-c 保存会话 Cookie 到文件）
curl -X POST https://localhost:8443/api/auth/login \
  -H "Content-Type: application/json" \
  -c cookies.txt \
  -d '{"email":"user@example.com","password":"MyPass123","code":"477256"}'
# → {"uuid":"xxx","email":"user@example.com","message":"登录成功"}
```

### 7.3 分块上传视频

```bash
UUID="your-uuid-here"
FILE="test.mp4"
SIZE=$(stat -c%s "$FILE")
CHUNKS=1   # 文件 ≤5MB 时 1 块，否则需计算

# 1. 初始化上传
INIT=$(curl -s -X POST https://localhost:8443/api/videos/upload/init \
  -H "Content-Type: application/json" \
  -b cookies.txt \
  -d "{\"uuid\":\"$UUID\",\"filename\":\"test.mp4\",\"totalSize\":$SIZE,\"totalChunks\":$CHUNKS,\"mimeType\":\"video/mp4\"}")
echo "$INIT"
UPLOAD_ID=$(echo "$INIT" | sed 's/.*"upload_id":"\([^"]*\)".*/\1/')
VIDEO_ID=$(echo "$INIT" | sed 's/.*"video_id":"\([^"]*\)".*/\1/')

# 2. 上传分块（PUT 请求，body 为二进制）
curl -s -X PUT "https://localhost:8443/api/videos/upload/${UPLOAD_ID}/chunk/0" \
  -H "Content-Type: application/octet-stream" \
  -b cookies.txt \
  --data-binary @"$FILE"
# → {"message":"ok"}

# 3. 完成上传（合并、加密、持久化）
curl -s -X POST "https://localhost:8443/api/videos/upload/${UPLOAD_ID}/complete" \
  -H "Content-Type: application/json" \
  -b cookies.txt \
  -d '{"title":"My Video"}'
# → {"video_id":"xxx","message":"上传完成"}
```

### 7.4 视频列表

```bash
curl -s https://localhost:8443/api/videos -b cookies.txt
# → [{"video_id":"xxx","title":"My Video","original_filename":"test.mp4","size":3145728,...}]
```

### 7.5 在线播放（支持拖拽）

```bash
curl -s -o /dev/null -w "HTTP %{http_code}, Size: %{size_download}" \
  https://localhost:8443/api/videos/{video_id}/stream -b cookies.txt
# → HTTP 200, Size: 3145728
```

前端 `<video>` 标签直接指向该 URL 即可播放，浏览器会自动发送 Range 请求实现拖拽。

### 7.6 下载视频

```bash
curl -s -o downloaded.mp4 -w "HTTP %{http_code}" \
  https://localhost:8443/api/videos/{video_id}/download -b cookies.txt
# → HTTP 200
```

### 7.7 完整性校验

```bash
curl -s -X POST https://localhost:8443/api/videos/{video_id}/verify -b cookies.txt
# → {"match":true}    数据完好
# → {"match":false}   数据被篡改或损坏
```

### 7.8 删除视频

```bash
curl -s -X DELETE https://localhost:8443/api/videos/{video_id} -b cookies.txt
# → {"message":"已删除"}
```

### 7.9 查询审计日志

```bash
# 分页查询（page 从 0 开始），可过滤 action 类型
curl -s "https://localhost:8443/api/admin/audit-logs?page=0&size=20" -b cookies.txt
curl -s "https://localhost:8443/api/admin/audit-logs?action=LOGIN&page=0&size=10" -b cookies.txt
```

### 7.10 退出登录

```bash
curl -s -X POST https://localhost:8443/api/auth/logout -b cookies.txt
# → {"message":"已退出登录"}
```

---

## 八、存储说明

加密视频统一存储在 MinIO 的 `vsec-videos` Bucket 中（路径：`{uuid}/{videoId}.enc`）。上传过程中的临时分片使用本地 `temp-dir`（`/tmp/vsec-storage`），上传完成后自动清理。

---

## 九、目录结构

```
VideoSecureSystem/
├── backend/                         # Spring Boot 后端
│   ├── pom.xml
│   ├── src/main/java/com/vsec/
│   │   ├── VSecApplication.java     # 启动入口
│   │   ├── config/                  # 配置（Security, Redis, Storage, ExceptionHandler）
│   │   ├── controller/              # 控制器（Auth, Video, AuditLog）
│   │   ├── crypto/                  # 密码学工具（SM3, SM4, Argon2id, HKDF）
│   │   ├── entity/                  # JPA 实体（User, Video, AuditLog）
│   │   ├── repository/              # 数据访问层
│   │   ├── service/                 # 业务逻辑（Auth, Video, AuditLog）
│   │   └── storage/                 # 存储抽象（StorageService 接口 + MinIO 实现）
│   └── src/main/resources/
│       └── application.yml          # 应用配置
├── frontend/                        # Vue 3 前端
├── HOW_TO_USE.md                    # 本文件
└── README.md                        # 项目总览
```

---

## 十、安全清单

| 项目 | 开发环境状态 | 生产环境要求 |
|------|-------------|-------------|
| Redis 密码 | 已设置 (`vsec123`) | 设置强密码 |
| Redis RDB 持久化 | 已关闭 (`save ""`) | 必须 `save ""` 关闭 |
| Redis AOF 持久化 | 已关闭 | 必须关闭 |
| MinIO 凭证 | `minioadmin/minioadmin` | 通过环境变量修改 |
| 邮件验证码 | SMTP 真实发送 (QQ) | SMTP 真实发送 |
| HTTPS | 已启用 (8443 自签名) | 必须启用 HTTPS |
| Cookie secure | `true` | 必须 `true` |
| MP4 格式校验 | 服务端 ftyp 魔数校验 | 后端验证文件头魔数 `ftyp` |
| 会话超时 | 30 分钟 | 按安全策略调整 |

---

## 十一、常见问题

**Q: 启动后端报 "Connection refused"？**
A: 检查 PostgreSQL 和 Redis 是否已启动（见第四节）。

**Q: MinIO 模式下上传失败？**
A: 确认 MinIO 服务已启动，`http://localhost:9000` 可访问，Bucket `vsec-videos` 会自动创建。

**Q: 验证码收不到？**
A: 开发模式验证码打印在后端控制台，搜索 `[DEV] 验证码:`。

**Q: 操作提示"未登录或会话已过期"？**
A: 会话有效期 30 分钟，超时需重新登录。确认请求携带了正确的 Cookie。

**Q: 上传提示"分块未全部上传"？**
A: 检查是否每个分块都返回 `{"message":"ok"}`，分块序号从 0 到 totalChunks-1。

**Q: 如何查看 MinIO 中的加密文件？**
A: 浏览器打开 `http://localhost:9001`，用 `minioadmin/minioadmin` 登录，进入 `vsec-videos` Bucket。文件以 `{uuid}/{videoId}.enc` 路径存储，内容为 SM4 加密密文，无法直接播放。
