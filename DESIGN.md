# Telegram Bangumi Bot - 技术设计文档

## 项目概述

一个 Telegram 机器人，用于提醒用户追番更新。支持多用户，通过 Bangumi.tv API 同步用户追番列表。

## 技术栈

- **语言**: Kotlin
- **框架**: Spring Boot 3.x
- **构建工具**: Maven
- **数据库**: PostgreSQL + Spring Data JPA
- **部署**: Docker (本地服务器)

## 依赖库

| 类别 | 库 | 说明 |
|------|-----|------|
| **Telegram Bot** | org.telegram:telegrambots-spring-boot-starter | 官方 Telegram Bot SDK，Spring Boot 集成 |
| **HTTP Client** | Ktor Client | 调用 Bangumi API |
| **JSON** | Jackson (Spring Boot 默认) | JSON 序列化 |
| **Database** | Spring Data JPA + Hibernate | ORM 和数据访问 |
| **Scheduler** | Spring Scheduling (@Scheduled) | 定时任务 |
| **Validation** | Spring Validation | 参数校验 |

## Bangumi API

**文档**: https://bangumi.github.io/api/

**认证方式**: Bearer Token (用户在 https://next.bgm.tv/demo/access-token 生成)

### 使用的 API 端点

| 端点 | 方法 | 用途 |
|------|------|------|
| `/v0/me` | GET | 获取当前用户信息，验证 token 有效性 |
| `/v0/users/{username}/collections` | GET | 获取用户追番列表 |
| `/calendar` | GET | 每日放送表 (按星期几分组) |
| `/v0/subjects/{subject_id}` | GET | 获取番剧详情 |
| `/v0/episodes` | GET | 获取剧集列表 (需要 subject_id 参数) |
| `/v0/users/-/collections/{subject_id}/episodes` | GET | 用户的剧集观看状态 |

## Bot 命令

| 命令 | 描述 |
|------|------|
| `/start` | 欢迎信息，使用说明 |
| `/bindtoken <token>` | 绑定 Bangumi access token |
| `/unbind` | 解除绑定 |
| `/sync` | 手动同步追番列表 |
| `/list` | 显示当前追番列表 |
| `/status` | 查看账号绑定状态和订阅数量 |
| `/settings` | 设置提醒时间、每日汇总开关等 |
| `/help` | 帮助信息 |

## 数据库设计

### users 表 - 用户信息

```sql
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    telegram_id BIGINT UNIQUE NOT NULL,
    telegram_username VARCHAR(255),
    bangumi_token VARCHAR(512),        -- 加密存储
    bangumi_user_id INT,
    bangumi_username VARCHAR(255),
    daily_summary_enabled BOOLEAN DEFAULT true,
    daily_summary_time TIME DEFAULT '10:00',
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);
```

### subscriptions 表 - 用户追番

```sql
CREATE TABLE subscriptions (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES users(id) ON DELETE CASCADE,
    subject_id INT NOT NULL,           -- Bangumi subject ID
    subject_name VARCHAR(512),
    subject_name_cn VARCHAR(512),
    total_episodes INT,
    air_weekday INT,                   -- 0-6, NULL if finished
    notify_enabled BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT NOW(),
    UNIQUE(user_id, subject_id)
);
```

### episode_notifications 表 - 已通知剧集记录

```sql
CREATE TABLE episode_notifications (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES users(id) ON DELETE CASCADE,
    subject_id INT NOT NULL,
    episode_id INT NOT NULL,
    episode_number INT,
    notified_at TIMESTAMP DEFAULT NOW(),
    UNIQUE(user_id, episode_id)
);
```

## 调度任务

### 1. 剧集更新检查 (Episode Check Worker)

- **频率**: 每 15 分钟
- **逻辑**:
  1. 获取所有已绑定用户的订阅列表
  2. 对每个订阅，调用 `/v0/episodes?subject_id=X` 获取剧集
  3. 比对 `episode_notifications` 表，找出未通知的新剧集
  4. 发送 Telegram 通知
  5. 记录到 `episode_notifications` 表

### 2. 追番同步 (Subscription Sync Worker)

- **频率**: 每 6 小时
- **逻辑**:
  1. 获取所有已绑定用户
  2. 调用 `/v0/users/{username}/collections` 同步追番列表
  3. 新增/删除 `subscriptions` 表记录

### 3. 每日汇总 (Daily Summary Worker)

- **频率**: 按用户设置的时间 (默认 10:00)
- **逻辑**:
  1. 获取今日是星期几
  2. 查询用户订阅中 `air_weekday` 匹配的番剧
  3. 发送今日放送汇总

## 系统架构

```
┌─────────────────┐     ┌──────────────────┐     ┌─────────────┐
│  Telegram Bot   │────▶│   Bot Service    │────▶│ PostgreSQL  │
└─────────────────┘     └──────────────────┘     └─────────────┘
                               │
                               ▼
                        ┌──────────────────┐
                        │   Bangumi API    │
                        │   api.bgm.tv     │
                        └──────────────────┘

调度器:
┌───────────────────┐  ┌───────────────────┐  ┌───────────────────┐
│ Episode Checker   │  │ Subscription Sync │  │  Daily Summary    │
│ (每15分钟)         │  │ (每6小时)          │  │  (用户设定时间)    │
└───────────────────┘  └───────────────────┘  └───────────────────┘
```

## Docker 部署

```yaml
# docker-compose.yml 结构
services:
  bot:
    build: .
    environment:
      - TELEGRAM_BOT_TOKEN=xxx
      - DATABASE_URL=postgres://...
    depends_on:
      - db

  db:
    image: postgres:16
    volumes:
      - pgdata:/var/lib/postgresql/data
```

## 安全与错误处理

### Token 加密

使用 PostgreSQL `pgcrypto` 扩展进行数据库层加密:

```sql
-- 启用 pgcrypto
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- 加密存储
UPDATE users SET bangumi_token = pgp_sym_encrypt('raw_token', 'encryption_key');

-- 解密读取
SELECT pgp_sym_decrypt(bangumi_token::bytea, 'encryption_key') FROM users;
```

加密密钥通过环境变量 `TOKEN_ENCRYPTION_KEY` 传入。

### 重试策略

Bangumi API 调用使用指数退避重试:

- 最大重试次数: 3
- 初始延迟: 1 秒
- 退避因子: 2 (1s → 2s → 4s)
- 可重试的 HTTP 状态码: 429, 500, 502, 503, 504

### 日志

使用 Spring Boot 默认的 Logback，日志级别:
- 生产环境: INFO
- 开发环境: DEBUG

## 项目结构 (Spring Boot)

```
src/main/kotlin/com/example/bangumi/
├── BangumiApplication.kt           # Spring Boot 入口
├── config/
│   └── BotConfig.kt                # Bot 配置类
├── bot/
│   ├── BangumiBot.kt               # Telegram Bot 主类
│   └── command/                    # 命令处理器
│       ├── StartCommand.kt
│       ├── BindCommand.kt
│       ├── ListCommand.kt
│       └── ...
├── entity/
│   ├── User.kt                     # JPA Entity
│   ├── Subscription.kt
│   └── EpisodeNotification.kt
├── repository/
│   ├── UserRepository.kt           # Spring Data JPA Repository
│   ├── SubscriptionRepository.kt
│   └── EpisodeNotificationRepository.kt
├── service/
│   ├── BangumiApiService.kt        # Bangumi API 调用
│   ├── SubscriptionService.kt      # 订阅业务逻辑
│   └── NotificationService.kt      # 通知服务
├── scheduler/
│   ├── EpisodeCheckScheduler.kt    # 剧集检查 (每15分钟)
│   ├── SubscriptionSyncScheduler.kt # 追番同步 (每6小时)
│   └── DailySummaryScheduler.kt    # 每日汇总
└── client/
    └── BangumiClient.kt            # Ktor HTTP Client
```

## 开发计划 (TDD)

采用测试驱动开发，每个功能模块先写测试验证可行性，再进行完整开发。

### Phase 1: 基础设施验证

| 步骤 | 测试目标 | 验证内容 |
|------|----------|----------|
| 1.1 | Maven 项目搭建 | Spring Boot 能正常启动 |
| 1.2 | PostgreSQL 连接 | JPA Entity 能正常读写 |
| 1.3 | Bangumi API 连接 | Ktor Client 能调用 `/calendar` 端点 |
| 1.4 | Telegram Bot 连接 | Bot 能收发消息 |

### Phase 2: 核心功能

| 步骤 | 测试目标 | 验证内容 |
|------|----------|----------|
| 2.1 | Token 验证 | 调用 `/v0/me` 验证 token 有效性 |
| 2.2 | 获取追番列表 | 调用 `/v0/users/{username}/collections` |
| 2.3 | 获取剧集信息 | 调用 `/v0/episodes` |
| 2.4 | Token 加密存储 | pgcrypto 加密/解密正常工作 |

### Phase 3: Bot 命令

| 步骤 | 测试目标 | 验证内容 |
|------|----------|----------|
| 3.1 | /start 命令 | 返回欢迎消息 |
| 3.2 | /bindtoken 命令 | 绑定并验证 token |
| 3.3 | /sync 命令 | 同步追番列表到数据库 |
| 3.4 | /list 命令 | 显示用户追番列表 |

### Phase 4: 定时任务

| 步骤 | 测试目标 | 验证内容 |
|------|----------|----------|
| 4.1 | 剧集检查逻辑 | 能检测到新剧集 |
| 4.2 | 通知发送 | 能发送 Telegram 消息 |
| 4.3 | 每日汇总 | 按星期筛选今日放送 |
| 4.4 | 定时触发 | @Scheduled 正常触发 |

### Phase 5: 完善与部署

| 步骤 | 测试目标 | 验证内容 |
|------|----------|----------|
| 5.1 | Docker 构建 | 镜像能正常构建运行 |
| 5.2 | docker-compose | 多容器编排正常 |
| 5.3 | 错误处理 | 重试和降级正常工作 |

### 杂项 todo

- 推送中的信息从文字改为图片（从模板程序化生成）
- 多集同时放送时，聚合每个番剧的消息为一条
