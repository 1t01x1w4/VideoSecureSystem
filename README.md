# VSec-Storage：视频大数据安全存储系统

## 一、项目核心目标

1. **本地单机部署**，这台机器同时充当服务器。
2. 用户通过**邮箱验证码 + 口令**登录（双因子，防止爆破和中间人攻击）。
3. 每个用户拥有唯一标识符 `UUID`。
4. 使用 **SM4 对称加密算法** 对所有上传视频加密（CTR 模式支持随机访问）。
5. 密文视频存入 **MinIO** 对象存储。
6. 提供安全的加密视频**在线查看**和**下载**（支持拖拽播放）。
7. 系统需体现**大数据**特点：**分块上传/下载、流式加解密、完整性校验、审计日志**。

## 二、核心安全架构（信封加密）

系统遵循 **“一视频一密钥”** 原则，采用信封加密架构：

- **用户原始口令**：仅由用户掌握，不存储。
- **主密钥 MK**：通过 Argon2id + HKDF 从口令派生，仅在登录期间存在于服务端内存（Session/Redis），永不落盘。
- **数据加密密钥 DEK**：每个视频上传时随机生成的 16 字节密钥（SM4 仅支持 128 位）。
- **加密后的 DEK**：由 MK 加密后的 DEK，存储在数据库中。
- **SM3 哈希检索索引**：上传时对视频标题/文件名做 bigram 分词 + HMAC-SM3(keyword, server_secret) 存入索引表，搜索时同态计算不可逆匹配，实现加密检索。

## 三、技术栈

| 层级 | 推荐方案 | 备注 |
|------|----------|------|
| 后端 | **Java 21 + Spring Boot 3.x** | Spring Security, Bouncy Castle 国密实现 |
| 前端 | **Vue 3 + TypeScript + Vite** | 播放器使用 Video.js |
| 数据库 | **PostgreSQL** | 元数据、审计日志 |
| 对象存储 | **MinIO** | 本地部署，兼容 S3 协议 |
| 缓存/会话 | **Redis** | 会话管理、频率限制 |
| 加解密 | **SM4-CTR + SM3** | Bouncy Castle 实现 |
| 密钥派生 | **Argon2id + HKDF** | 生成 `auth_key` 与 `enc_key` (MK) |
| 验证码发送 | **QQ SMTP** 真实发送，可切换控制台输出 | `spring.mail.dev-mode` 切换 |
| 通信安全 | **HTTPS**（自签名证书，JDK keytool + PKCS12） | 端口 8443，Cookie secure 已启用 |

## 四、功能需求

### 1. 用户注册与登录
- **注册**：
  - 输入邮箱、口令（密码强度：至少8位，含大小写字母和数字）。
  - 系统发送 6 位数字验证码到邮箱（QQ SMTP 真实发送，可通过 `spring.mail.dev-mode` 切换回控制台）。
  - 验证码有效期 **5 分钟**，一次有效，验证后立即失效。
  - 验证通过后，系统为用户：
    - 生成全局唯一的 `UUID` 作为用户标识。
    - 生成随机盐 `salt`（32 字节）。
    - 使用 **Argon2id + HKDF** 从口令派生两个密钥：
      - `auth_key`（用于登录验证，存储到数据库）
      - `enc_key`（主密钥 MK，**不存储到数据库**，仅登录会话期间存在于服务端内存/Redis）
    - 存储信息：`uuid, email, salt, auth_key`。
- **登录**：
  - 输入邮箱和口令，点击“获取验证码”触发邮件。
  - 输入验证码 + 口令进行登录。
  - 登录失败限制：每邮箱每 5 次连续失败锁定 15 分钟。
  - 验证通过后，服务端根据数据库中的 `salt` 和用户提供的口令，重新派生 `auth_key` 和 `enc_key`。比对 `auth_key`，成功则在会话（Redis/Session）中临时保存 `enc_key`，用于后续视频加解密。
  - 会话超时时间：**30 分钟**，超时后 `enc_key` 从内存销毁。

### 2. 视频上传（加密存储）
- 上传表单：选择视频文件 + 视频名称（可选）。
- **文件格式限制**：系统**只接受 MP4 视频文件**（`video/mp4`），其他格式直接拒绝。
- **大文件分块上传**：文件在前端切为固定大小的块（**5 MB**），支持断点续传。
- 加密流程（服务端执行）：
  1. 为本次上传视频生成随机 **数据加密密钥 DEK**（16 字节）和随机 **CTR IV**（16 字节），暂存于 Redis（key: `upload:{uploadId}`，有效期 2 小时）。
  2. 前端将原始文件切片后逐块上传，服务端将每个分片以明文临时写入 `temp/{uploadId}/chunk_{n}.plain`。
  3. 全部分片上传完成后，触发合并加密：
     - 逐块读取明文分片，使用 **SM4-CTR** 加密。每个分块的计数器偏移量为 `chunk_index * chunkSize / 16`，通过 `adjustCounter(iv, blockOffset)` 调整 IV，确保整个文件的 CTR 计数器连续。
     - 同时增量计算原始文件的 **SM3 哈希**（`original_sm3`）和密文的 **SM3 哈希**（`encrypted_sm3`）。
     - 加密后的分块拼接写入 `storage/{uuid}/{videoId}.enc`。
  4. **信封加密**：用会话中的用户主密钥 `enc_key`（MK，16 字节）以 **SM4-CBC** 加密 `DEK`，得到 `EncryptedDEK`。CBC 模式下复用 CTR IV 作为初始化向量（因密钥不同，IV 复用安全）。
  5. 在 PostgreSQL 中保存视频元信息：`video_id, uuid, encrypted_dek, ctr_iv, original_sm3, encrypted_sm3, file_path, size, mime_type, created_at`。
  6. 清理临时分片目录和 Redis 上传状态。
- **会话密钥存储**：`enc_key`（MK）在登录时派生并以 **Base64 字符串** 存入 Spring Session（Redis）。避免 `byte[]` 在 `GenericJackson2JsonRedisSerializer` 下反序列化丢失类型信息。
- **存储方式**：加密视频统一存储在 **MinIO 对象存储**（`vsec-videos` Bucket），上传阶段的临时分片使用本地 `temp-dir`。
- 这样实现 **一视频一密钥**，即使单个视频密钥泄露，其他视频不受影响。

### 3. 视频查看（在线解密播放）
- 用户在已登录状态下请求播放视频。
- 服务端：
  1. 校验 Session 中是否存在 MK。
  2. 根据 `video_id` 获取元信息及 `EncryptedDEK`。
  3. 使用会话中的 `enc_key` 解密 `EncryptedDEK` 得到 `DEK`。
  4. 从 MinIO 流式读取密文数据，边读边用 `DEK` 进行 **SM4-CTR 解密**。
  5. 将解密后的视频流以 `Content-Type: video/mp4` 返回给前端。**必须支持 Range 请求**（`Accept-Ranges: bytes`），由于 CTR 模式是流密码，可根据字节偏移调整计数器直接定位解密，实现拖拽播放。
- **安全性要求**：解密过程中 `DEK` 和明文帧只在内存中短暂存在，永不写入磁盘。请求结束后释放。

### 4. 视频下载
- 与查看类似，设置响应头 `Content-Disposition: attachment` 强制下载。
- 同样流式解密后返回，不临时落盘。

### 5. 完整性校验
- 上传完成时校验 `SM3` 值，若用户怀疑数据损坏，可提供手动校验接口，重新计算存储密文的 SM3 与数据库中的 `encrypted_sm3` 对比。

### 6. 审计日志
- 记录所有关键操作：注册、登录（成功/失败）、上传、查看、下载、异常。
- 日志字段：时间、用户 UUID、操作类型、IP、操作结果。
- 存储到数据库 `audit_logs` 表，提供管理页面查询（可简单作为 API 接口）。

## 五、安全约束（严格遵循）

- **禁止**：在日志中打印任何密钥（MK 或 DEK）。
- **禁止**：将 `enc_key` 以明文形式存储在数据库或本地配置文件中。
- **必须**：所有的加密操作均在服务端完成。
- **必须**：在 Session 失效或用户退出时，显式抹除内存中的密钥字节数组。
- **必须**：视频解密流不缓存到磁盘。
- **Redis 安全配置**：若使用 Redis 保存会话（包含 `enc_key`），必须关闭 RDB 和 AOF 持久化，防止密钥通过持久化文件落盘；且 Redis 应配置密码认证，绑定内网接口。
- **文件格式校验**：必须在服务端严格校验上传文件头或 MIME 类型，只放行标准 MP4 格式，防止恶意文件上传。

## 六、数据库表结构设计（核心表）

### users
| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| uuid | VARCHAR(36) | PRIMARY KEY | 用户唯一标识（UUID） |
| email | VARCHAR(255) | UNIQUE, NOT NULL | 邮箱 |
| salt | BYTEA | NOT NULL | 随机盐值（32字节） |
| auth_key | BYTEA | NOT NULL | Argon2id+HKDF 派生的认证密钥 |
| created_at | TIMESTAMP | NOT NULL DEFAULT NOW() | 注册时间 |
| status | VARCHAR(20) | NOT NULL DEFAULT 'active' | 账户状态（active/locked） |

### videos
| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| video_id | VARCHAR(36) | PRIMARY KEY | 视频唯一标识（UUID） |
| uuid | VARCHAR(36) | FOREIGN KEY → users.uuid | 所属用户 |
| title | VARCHAR(255) | | 视频名称 |
| original_filename | VARCHAR(255) | NOT NULL | 原始文件名 |
| encrypted_dek | BYTEA | NOT NULL | 经 MK（SM4-CBC）加密后的 DEK |
| ctr_iv | BYTEA | NOT NULL | SM4-CTR 加密的初始向量（16 字节，同时作为 DEK 加密的 CBC IV） |
| original_sm3 | BYTEA | NOT NULL | 原始文件 SM3 哈希 |
| encrypted_sm3 | BYTEA | NOT NULL | 密文文件 SM3 哈希 |
| file_path | VARCHAR(512) | NOT NULL | 加密文件存储路径（格式：`{uuid}/{videoId}.enc`） |
| mime_type | VARCHAR(50) | NOT NULL DEFAULT 'video/mp4' | 文件类型（固定 mp4） |
| size | BIGINT | NOT NULL | 原始文件大小（字节） |
| created_at | TIMESTAMP | NOT NULL DEFAULT NOW() | 上传时间 |

### audit_logs
| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | BIGSERIAL | PRIMARY KEY | 自增主键 |
| uuid | VARCHAR(36) | | 操作用户 UUID（可空） |
| action | VARCHAR(50) | NOT NULL | 操作类型（LOGIN/REGISTER/UPLOAD/VIEW/DOWNLOAD/ERROR） |
| target_id | VARCHAR(36) | | 操作目标（如 video_id） |
| ip | VARCHAR(45) | | 客户端 IP |
| user_agent | VARCHAR(512) | | 客户端 User-Agent |
| result | VARCHAR(20) | NOT NULL | 操作结果（SUCCESS/FAIL） |
| detail | TEXT | | 额外信息（禁止包含密钥） |
| created_at | TIMESTAMP | NOT NULL DEFAULT NOW() | 操作时间 |

### video_keywords（加密检索索引）
| 字段名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| id | BIGSERIAL | PRIMARY KEY | 自增主键 |
| video_id | VARCHAR(36) | FOREIGN KEY → videos.video_id ON DELETE CASCADE | 所属视频 |
| keyword_hash | BYTEA | NOT NULL | HMAC-SM3(keyword, server_secret) 的 32 字节哈希 |

> 唯一约束 `UNIQUE(video_id, keyword_hash)` 防止重复索引；B-tree 索引 `idx_keyword_hash` 加速等值查询。删除视频时 CASCADE 自动清理索引。

## 七、API 路由设计

> 所有 API 前缀：`/api`
> 除注册、登录外，其余接口需在 Header 中携带：`Cookie: SESSION=xxx`（或 `Authorization: Bearer`，视最终会话方案而定）

### 1. 认证模块
- `POST /api/auth/register`  
  注册新用户（需邮箱验证码）  
  Request: `{ email, password, code }`  
  Response: `{ uuid, email }`

- `POST /api/auth/login`  
  用户登录（需验证码）  
  Request: `{ email, password, code }`  
  Response: `{ message }` 并设置会话 Cookie

- `POST /api/auth/logout`  
  退出登录，销毁服务端会话及内存密钥

- `POST /api/auth/code`  
  发送邮箱验证码（注册/登录前调用）  
  Request: `{ email, purpose: "register"|"login" }`  
  Response: `{ message }` （含防刷限制）

### 2. 视频管理模块
- `POST /api/videos/upload/init`  
  初始化分块上传，生成 DEK 和 CTR IV 存入 Redis  
  Request: `{ filename, totalSize, totalChunks, mime_type }`  
  Response: `{ upload_id, video_id }`

- `PUT /api/videos/upload/{uploadId}/chunk/{chunkIndex}`  
  上传单个分块（body 为原始二进制数据，暂存为明文）  
  Content-Type: `application/octet-stream`  
  Response: 200 OK

- `POST /api/videos/upload/{uploadId}/complete`  
  合并分块 → SM4-CTR 逐块加密 → SM3 哈希 → SM4-CBC 信封加密 DEK → 持久化  
  Request: `{ title }`  
  Response: `{ video_id, message }`

- `GET /api/videos`  
  获取当前用户的视频列表（支持加密检索）  
  Query: `?keyword=旅行`（可选，基于 SM3 哈希索引的不可逆密文匹配）  
  Response: `[{ video_id, title, original_filename, size, mime_type, created_at }]`

- `DELETE /api/videos/{videoId}`  
  删除视频（加密文件 + 数据库元数据）

### 3. 视频播放与下载
- `GET /api/videos/{video_id}/stream`  
  流式解密播放（支持 Range 头，SM4-CTR 随机访问解密）  
  Response Headers: `Content-Type: video/mp4`, `Accept-Ranges: bytes`  
  无 Range → 200 + 全文件流式解密；有 Range → 206 + 解密指定区间

- `GET /api/videos/{video_id}/download`  
  流式解密下载（强制 `Content-Disposition: attachment`，不临时落盘）

### 4. 校验与管理
- `POST /api/videos/{videoId}/verify`  
  重新计算密文 SM3 并与数据库比对，返回校验结果  
  Response: `{ match }`

- `GET /api/admin/audit-logs`  
  分页查询审计日志（自动限制为当前用户）  
  Query: `?page=0&size=20&action=LOGIN`

## 八、开发任务路线图

### 第一阶段：基础设施与认证 ✅ 已完成
- [x] 初始化 Spring Boot 3 工程，配置 PostgreSQL 和 Redis（关闭 Redis 持久化）。
- [x] 基于 Bouncy Castle 实现密码学工具类（Argon2id, HKDF, SM3, SM4-CTR, SM4-CBC）。
- [x] 完成用户注册与登录逻辑（邮箱验证码 + 口令双因子，Spring Security 会话管理）。
- [x] 修复登录后 SecurityContext 丢失导致跳转回登录页的问题。
- [x] 修复 `enc_key` 在 Spring Session Redis 中 `byte[]` 序列化不兼容的问题（改用 Base64 字符串存储）。

### 第二阶段：存储与上传 ✅ 已完成
- [x] 实现大文件切片上传接口（前端切块 + 服务端接收）。
- [x] 实现 SM4-CTR 分块加密（计数器按块偏移调整），SM3 完整性哈希。
- [x] 实现信封加密：SM4-CBC 用 MK 加密 DEK。
- [x] 加密文件存储至 MinIO 对象存储（`vsec-videos` Bucket）。
- [x] 视频完整性校验接口（重新计算密文 SM3 与数据库比对）。
- [x] 集成 MinIO SDK（`StorageService` 接口 + `MinioStorageService` 实现）。

### 第三阶段：流式解密播放 ✅ 已完成
- [x] 实现支持 Range 请求的视频流式解密播放接口（`SM4-CTR` 随机访问）。→ `VideoController.streamVideo`
- [x] 实现视频流式解密下载接口。→ `VideoController.downloadVideo`
- [x] 确保前端播放器能够处理流式返回的 `video/mp4` 数据。→ `Player.vue`（原生 `<video>` + Range 支持）
- **内存效率**：1MB 块流式解密，峰值内存 ~2MB，全文件不加载到堆；Range 请求仅读取所需密文区间。

### 第四阶段：审计与优化 ✅ 已完成
- [x] 实现审计日志 API（`/api/admin/audit-logs`）。→ `AuditLogController` + `AuditLogService`
- [x] 前端完善：上传进度条 → `Upload.vue`；视频列表 → `Dashboard.vue`；播放器 → `Player.vue`。
- [x] 审计日志覆盖：REGISTER / LOGIN(成功+失败) / UPLOAD / VIEW / DOWNLOAD / DELETE。
- [x] QQ SMTP 真实邮件发送（`spring.mail.dev-mode: false`，验证码到达邮箱）。

### 第四阶段改进项 ✅ 全部完成
- [x] **HTTPS 支持**（JDK 自签名证书 + PKCS12，后端端口 8443）
- [x] **服务端 MP4 文件头校验**（`chunkIndex == 0` 时验证 `ftyp` 魔数 + major brand）
- [x] **Redis 安全加固**：`redis.vsec.conf` 加载启动，密码认证 + `save ""` 关闭 RDB + `appendonly no`
- [x] **前端上传类型过滤**：`accept="video/mp4,.mp4"` + 拖拽实时文件类型检测 + 非 MP4 红色警告
- [x] **加密视频检索**：SM3 哈希索引 + bigram 分词，支持中英文混合检索，不可逆反推原文（`GET /api/videos?keyword=`）

> **当前环境**：PostgreSQL（`localhost:5432`）、Redis（`localhost:6379`，已设置密码 `vsec123`）、MinIO（`localhost:9000` / `:9001`）已配置运行。后端 HTTPS 端口 `8443`（自签名证书），前端 `http://localhost:5173` 通过 Vite 代理转发 API 请求到 HTTPS 后端。

> **使用说明**：详见 [HOW_TO_USE.md](HOW_TO_USE.md)，包含完整的服务启动和 API 调用指南。