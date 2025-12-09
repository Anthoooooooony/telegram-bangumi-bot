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
- Telegram Bot API (telegrambots-longpolling 8.0)
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

**剧集编号**:
- `Episode.sort`: 全局集数 (跨季累计，用于存储和比较)
- `Episode.ep`: 本季集数 (显示给用户)

**新订阅处理**:
`SubscriptionService` 初始化新订阅时，将 `lastNotifiedEp` 设为当前最新已播集数，避免推送历史剧集

**播放平台链接**:
`BangumiDataClient` 从 bangumi-data (jsdelivr CDN) 获取流媒体平台数据，通知消息使用 MarkdownV2 格式，特殊字符需转义

## 环境变量

| 变量 | 说明 |
|------|------|
| `TELEGRAM_BOT_TOKEN` | Telegram Bot Token (必需) |
| `ENCRYPTION_KEY` | Token 加密密钥 |
| `DB_PASSWORD` | PostgreSQL 密码 |

## 编码风格

**语言**: 注释、日志、文档均使用中文

**文档注释**: 仅关键公开方法添加 KDoc，保持简洁

**日志规范**:
- `INFO`: 业务流程关键节点（任务开始/完成、同步结果）
- `DEBUG`: 调试细节（API 调用参数、中间状态）
- `WARN`: 可恢复异常（单个订阅检查失败，不影响整体）
- `ERROR`: 不可恢复异常（数据库连接失败、Bot 启动失败）

**异常处理**: 业务方法返回 Result/sealed class 封装成功失败，避免直接抛出业务异常

## 开发流程

项目采用 TDD，新功能开发流程：
1. 先编写测试用例
2. 运行测试确认失败
3. 实现功能代码
4. 运行测试确认通过
5. 重构优化
6. 如有需要，更新 CLAUDE.md

提交前必须确保 `mvn test` 全部通过

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
| 端点 | 用途 |
|------|------|
| `GET /v0/me` | 验证 token，获取用户信息 |
| `GET /v0/users/{username}/collections` | 获取用户追番列表 |
| `GET /v0/episodes?subject_id=X` | 获取剧集列表 |
| `GET /calendar` | 每日放送表 |
