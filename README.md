# Telegram Bangumi Bot

Telegram 追番提醒机器人，连接 [Bangumi](https://bgm.tv) 账号，自动推送新剧集更新通知。

## 功能

- 绑定 Bangumi 账号，同步"在看"列表
- 每 15 分钟检查新剧集，发现更新即时推送
- 每日定时汇总当天有更新的追番

## Bot 命令

| 命令                   | 说明               |
|----------------------|------------------|
| `/start`             | 开始使用             |
| `/bindtoken <token>` | 绑定 Bangumi Token |
| `/unbind`            | 解除绑定             |
| `/list`              | 显示追番列表           |
| `/status`            | 查看绑定状态           |

## 部署

### Docker 部署 (推荐)

```bash
# 1. 克隆项目
git clone <repo-url>
cd telegram-bangumi-bot

# 2. 配置环境变量
cp .env.example .env
vim .env  # 填入 TELEGRAM_BOT_TOKEN

# 3. 启动服务
docker-compose up -d

# 4. 查看日志
docker-compose logs -f app
```

### 本地开发

```bash
# 启动 PostgreSQL
docker-compose up -d db

# 运行应用
TELEGRAM_BOT_TOKEN=<your_token> mvn spring-boot:run
```
