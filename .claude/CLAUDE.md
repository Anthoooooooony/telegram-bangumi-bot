# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 注意

开发过程中应同步更新必要的内容到 CLAUDE.md 和 README.md。

## 项目简介

Telegram 追番提醒机器人，连接 Bangumi.tv 账号，自动推送新剧集更新通知。

## 常用命令

```bash
# 构建项目
mvn clean package -DskipTests

# 运行测试 (需要 Docker，测试使用 Testcontainers)
mvn test

# 运行单个测试类
mvn test -Dtest=BangumiClientTest

# 本地开发运行 (需先启动数据库)
docker-compose up -d db
TELEGRAM_BOT_TOKEN=<token> mvn spring-boot:run

# Docker 部署
docker-compose up -d --build

# 查看应用日志
docker-compose logs -f app
```

## 技术栈

- Kotlin + Spring Boot 3.4 + Maven
- PostgreSQL + Spring Data JPA (Hibernate ddl-auto=update)
- Telegram Bot API (telegrambots-longpolling 9.x)
- Ktor Client (调用 Bangumi API)
- Testcontainers (集成测试)

## 架构概览

```
┌─────────────┐     ┌──────────────┐     ┌────────────┐
│ Telegram    │────▶│   BangumiBot │────▶│ PostgreSQL │
│ (Long Poll) │     │   Service层   │     └────────────┘
└─────────────┘     └──────────────┘
                           │
                           ▼
                    ┌──────────────┐
                    │ Bangumi API  │
                    │ api.bgm.tv   │
                    └──────────────┘
```

**定时任务**:
- `EpisodeCheckerTask`: 每 15 分钟检查新剧集，发送更新通知
- `DailySummaryTask`: 每日汇总当天有更新的追番
- `BangumiDataClient`: 每日 4:00 从 bangumi-data CDN 刷新播放平台数据

**核心流程**:
1. 用户通过 `/bindtoken` 绑定 Bangumi Token（绑定后自动同步追番列表）
2. 定时任务检查 Bangumi API 的剧集播出日期
3. 对比 `subscription.lastNotifiedEp`，有新集则推送 Telegram 消息

## 关键实现细节

**数据源整合**:
项目使用两个数据源，通过 `AnimeService` 整合：

| 数据源 | 来源 | 提供内容 |
|--------|------|---------|
| Bangumi API | `api.bgm.tv` | 番剧名称、封面、剧集列表、播出日期 (仅日期) |
| BangumiData | `cdn.jsdelivr.net` | 播放平台链接、精确播出时间 (含时分) |

`Anime` 实体存储整合后的番剧信息，`Subscription` 通过外键关联。

**播出时间判断**:
- 优先使用 BangumiData 的 `begin` + `broadcast` 计算精确播出时间
- 回退到 Bangumi API 的 `Episode.airdate` (仅日期)
- `AnimeService.isEpisodeAired()` 统一处理两种情况

**剧集编号**:
- `Episode.sort`: 全局集数 (跨季累计，用于存储和比较)
- `Episode.ep`: 本季集数 (仅 API 兼容，项目中不使用)

**新订阅处理**:
`SubscriptionService` 初始化新订阅时，将 `lastNotifiedEp` 设为当前最新已播集数，避免推送历史剧集

**播放平台链接**:
`BangumiDataClient` 从 bangumi-data (jsdelivr CDN) 获取流媒体平台数据和时间信息，通知消息使用 MarkdownV2 格式，特殊字符需转义

**资源缓存**:
`BangumiCacheService` 提供内存缓存，减少 API 调用和图片下载：
- 番剧详情 (SubjectDetail): 缓存 1 小时
- 剧集列表 (EpisodeResponse): 缓存 1 小时
- 封面图片 (BufferedImage): 缓存 24 小时
- 每 10 分钟自动清理过期条目

**速率限制**:
`RateLimiterService` 使用 Bucket4j 令牌桶算法限制每个用户的请求频率，防止滥用：
- 每个用户独立限流，使用 Caffeine 缓存，闲置 10 分钟后自动释放
- 默认 10 个突发令牌，每 3 秒恢复 1 个

## 环境变量

| 变量                                      | 说明                      |
|-----------------------------------------|-------------------------|
| `TELEGRAM_BOT_TOKEN`                    | Telegram Bot Token (必需) |
| `ENCRYPTION_KEY`                        | Token 加密密钥              |
| `DB_PASSWORD`                           | PostgreSQL 密码           |
| `RATE_LIMITER_BURST`                    | 突发令牌数 (默认 10)           |
| `RATE_LIMITER_RESTORE_INTERVAL_SECONDS` | 令牌恢复间隔秒数 (默认 3)         |

## 编码风格

**语言**: 注释、日志、文档均使用中文

**文档注释**: 仅关键公开方法添加 KDoc，保持简洁

**日志规范**:
- `INFO`: 业务流程关键节点（任务开始/完成、同步结果）
- `DEBUG`: 调试细节（API 调用参数、中间状态）
- `WARN`: 可恢复异常（单个订阅检查失败，不影响整体）
- `ERROR`: 不可恢复异常（数据库连接失败、Bot 启动失败）

**禁止魔法值**: 不要使用 -1、null 等魔法值表示错误状态，应抛出异常或使用 Result 类型

**注解 use-site target**: 构造函数参数上的注解必须添加显式 use-site target，明确注解的作用目标，例如：
- `@param:Value` - Spring 属性注入
- `@param:Lazy` - 延迟加载
- `@field:JsonProperty` - Jackson 序列化
- `@field:Column`, `@field:Id`, `@field:ManyToOne` 等 - JPA 实体字段

## 开发流程

项目采用 TDD，新功能开发流程：
1. 先编写测试用例
2. 运行测试确认失败
3. 实现功能代码
4. 运行测试确认通过
5. 重构优化
6. 如有需要，更新 CLAUDE.md

提交前必须确保 `mvn test` 全部通过

**Git 提交规范**:
- 每个功能/重构单独一个 commit，不要混合多个改动
- 不要直接提交到 `master`/`main` 分支，除非用户明确确认
- 在 `dev` 分支开发，测试通过后再合并到 `master`

## 版本管理

pom.xml 版本号需与 Docker 镜像版本保持一致。发布新版本时：
1. 更新 pom.xml 中的 `<version>`
2. 构建并推送 Docker 镜像时使用相同版本号

**多平台构建**: 生产服务器为 linux/amd64，开发机可能是 arm64 (Mac M系列)。构建镜像时必须使用 `docker buildx` 进行多平台构建：
```bash
docker buildx build --platform linux/amd64,linux/arm64 \
  -t anthooooony/telegram-bangumi-bot:latest \
  -t anthooooony/telegram-bangumi-bot:<version> \
  --push .
```

## Bangumi API

文档: https://bangumi.github.io/api/

认证: Bearer Token (用户在 https://next.bgm.tv/demo/access-token 生成)

**使用的端点**:

| 端点                                     | 用途              |
|----------------------------------------|-----------------|
| `GET /v0/me`                           | 验证 token，获取用户信息 |
| `GET /v0/users/{username}/collections` | 获取用户追番列表        |
| `GET /v0/episodes?subject_id=X`        | 获取剧集列表          |
| `GET /calendar`                        | 每日放送表           |

## 分支管理

| 分支       | 用途             |
|----------|----------------|
| `master` | 稳定发布分支，用于生产环境  |
| `dev`    | 开发分支，日常开发基于此分支 |

**分支命名规范** (基于 [Conventional Branch](https://conventional-branch.github.io/zh/)):

格式: `<type>/<description>`

| 类型          | 说明            | 示例                       |
|-------------|---------------|--------------------------|
| `feat/`     | 新功能           | `feat/daily-summary`     |
| `fix/`      | Bug 修复        | `fix/notification-error` |
| `hotfix/`   | 紧急修复          | `hotfix/security-patch`  |
| `refactor/` | 重构            | `refactor/cache-service` |
| `chore/`    | 杂项 (依赖更新、配置等) | `chore/update-deps`      |
| `release/`  | 发布分支          | `release/v1.2.0`         |

**命名规则**:
- 仅使用小写字母、数字、连字符 (`-`)
- 版本号可使用点 (`.`)，如 `release/v1.2.0`
- 关联 Issue 时包含编号: `feat/issue-123-new-feature`
- 描述简洁明确，体现分支目的

## Docker 镜像

镜像仓库: `anthooooony/telegram-bangumi-bot`

**镜像标签命名规范**:

| 标签         | 说明     | 触发条件        |
|------------|--------|-------------|
| `latest`   | 最新稳定版本 | 推送 `v*` 标签时 |
| `v1.x.x`   | 指定版本号  | 推送 `v*` 标签时 |
| `<branch>` | 分支最新版本 | 推送到任意分支时    |

**GitHub Actions 自动构建**:
- 推送到任意分支 → 构建并推送以分支名为标签的镜像 (如 `dev`, `feat-xxx`)
- 推送 `v*` 标签 → 构建并推送 `v*` + `latest` 镜像
