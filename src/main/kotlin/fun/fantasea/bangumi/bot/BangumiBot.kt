package `fun`.fantasea.bangumi.bot

import `fun`.fantasea.bangumi.service.SubscriptionService
import `fun`.fantasea.bangumi.service.UserService
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto
import org.telegram.telegrambots.meta.api.objects.InputFile
import java.io.ByteArrayInputStream
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand
import org.telegram.telegrambots.meta.generics.TelegramClient

@Component
class BangumiBot(
    @Value("\${telegram.bot.token}") private val botToken: String,
    private val userService: UserService,
    private val subscriptionService: SubscriptionService
) : SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private val log = LoggerFactory.getLogger(BangumiBot::class.java)
    private val telegramClient: TelegramClient = OkHttpTelegramClient(botToken)
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun getBotToken(): String = botToken

    override fun getUpdatesConsumer(): LongPollingUpdateConsumer = this

    /**
     * 启动时注册 Bot 命令到 Telegram
     */
    @PostConstruct
    fun registerCommands() {
        val commands = listOf(
            BotCommand("start", "开始使用"),
            BotCommand("bindtoken", "绑定 Bangumi token"),
            BotCommand("unbind", "解除绑定"),
            BotCommand("sync", "同步追番列表"),
            BotCommand("list", "显示追番列表"),
            BotCommand("status", "查看绑定状态")
        )

        try {
            val setMyCommands = SetMyCommands.builder()
                .commands(commands)
                .build()
            val result = telegramClient.execute(setMyCommands)
            log.info("注册 Bot 命令: {}", if (result) "成功" else "失败")
        } catch (e: Exception) {
            log.error("注册 Bot 命令失败: {}", e.message, e)
        }
    }

    override fun consume(update: Update) {
        if (update.hasMessage() && update.message.hasText()) {
            val chatId = update.message.chatId
            val userId = update.message.from.id
            val text = update.message.text
            val username = update.message.from.userName ?: update.message.from.firstName

            log.info("收到消息 from {} ({}): {}", username, userId, text)

            // 确保用户存在
            userService.getOrCreateUser(userId, username)

            when {
                text.startsWith("/start") -> handleStart(chatId, username)
                text.startsWith("/bindtoken") -> handleBindToken(chatId, userId, text)
                text.startsWith("/unbind") -> handleUnbind(chatId, userId)
                text.startsWith("/sync") -> handleSync(chatId, userId)
                text.startsWith("/list") -> handleList(chatId, userId)
                text.startsWith("/status") -> handleStatus(chatId, userId)
                else -> handleUnknown(chatId)
            }
        }
    }

    private fun handleStart(chatId: Long, username: String) {
        val message = """
            你好 $username！欢迎使用 Bangumi 追番提醒 Bot。

            可用命令:
            /start - 开始使用
            /bindtoken <token> - 绑定 Bangumi token
            /unbind - 解除绑定
            /sync - 同步追番列表
            /list - 显示追番列表
            /status - 查看绑定状态

            获取 Token: 访问 https://next.bgm.tv/demo/access-token
        """.trimIndent()
        sendMessage(chatId, message)
    }

    private fun handleBindToken(chatId: Long, userId: Long, text: String) {
        val parts = text.split(" ", limit = 2)
        if (parts.size < 2 || parts[1].isBlank()) {
            sendMessage(chatId, "请提供 Token。\n用法: /bindtoken <your_token>\n\n获取 Token: https://next.bgm.tv/demo/access-token")
            return
        }

        val token = parts[1].trim()
        sendMessage(chatId, "正在验证 Token...")

        scope.launch {
            val result = userService.bindToken(userId, token)
            if (result.success) {
                sendMessage(chatId, """
                    绑定成功！
                    Bangumi 用户: ${result.bangumiUsername}
                    昵称: ${result.bangumiNickname}

                    使用 /sync 同步追番列表
                """.trimIndent())
            } else {
                sendMessage(chatId, "绑定失败: ${result.error}")
            }
        }
    }

    private fun handleUnbind(chatId: Long, userId: Long) {
        val success = userService.unbindToken(userId)
        if (success) {
            sendMessage(chatId, "已解除 Bangumi 账号绑定。")
        } else {
            sendMessage(chatId, "解绑失败，请稍后重试。")
        }
    }

    private fun handleSync(chatId: Long, userId: Long) {
        sendMessage(chatId, "正在同步追番列表...")

        scope.launch {
            val result = subscriptionService.syncSubscriptions(userId)
            if (result.success) {
                sendMessage(chatId, "同步完成！共 ${result.syncedCount} 部在看。\n\n使用 /list 查看追番列表。")
            } else {
                sendMessage(chatId, "同步失败: ${result.error}")
            }
        }
    }

    private fun handleList(chatId: Long, userId: Long) {
        val subscriptions = subscriptionService.getUserSubscriptions(userId)

        if (subscriptions.isEmpty()) {
            sendMessage(chatId, "追番列表为空。\n\n请先使用 /bindtoken 绑定账号，然后 /sync 同步数据。")
            return
        }

        val sb = StringBuilder("追番列表 (${subscriptions.size} 部):\n\n")

        subscriptions.forEachIndexed { index, sub ->
            val name = sub.subjectNameCn?.takeIf { it.isNotBlank() } ?: sub.subjectName
            val eps = sub.totalEpisodes?.let { " (${it}集)" } ?: ""
            sb.append("${index + 1}. $name$eps\n")
        }

        sendMessage(chatId, sb.toString())
    }

    private fun handleStatus(chatId: Long, userId: Long) {
        val status = userService.getUserStatus(userId)

        if (status == null) {
            sendMessage(chatId, "用户信息不存在，请使用 /start 重新开始。")
            return
        }

        val message = if (status.isBound) {
            val subscriptions = subscriptionService.getUserSubscriptions(userId)
            """
                状态: 已绑定
                Bangumi 用户: ${status.bangumiUsername}
                追番数量: ${subscriptions.size}
                每日推送: ${if (status.dailySummaryEnabled) "开启 (${status.dailySummaryTime})" else "关闭"}
            """.trimIndent()
        } else {
            """
                状态: 未绑定

                请使用 /bindtoken <token> 绑定 Bangumi 账号
                获取 Token: https://next.bgm.tv/demo/access-token
            """.trimIndent()
        }

        sendMessage(chatId, message)
    }

    private fun handleUnknown(chatId: Long) {
        sendMessage(chatId, "未知命令，使用 /start 查看可用命令。")
    }

    fun sendMessage(chatId: Long, text: String) {
        try {
            val message = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .build()
            telegramClient.execute(message)
        } catch (e: Exception) {
            log.error("发送消息失败: {}", e.message, e)
        }
    }

    /**
     * 发送 Markdown 格式消息 (使用 MarkdownV2)
     */
    fun sendMessageMarkdown(chatId: Long, text: String) {
        try {
            val message = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .parseMode("MarkdownV2")
                .disableWebPagePreview(true)
                .build()
            telegramClient.execute(message)
        } catch (e: Exception) {
            log.error("发送 Markdown 消息失败: {}", e.message, e)
            // 降级为普通消息（去除 Markdown 标记）
            val plainText = text
                .replace("\\", "")
                .replace("*", "")
            sendMessage(chatId, plainText)
        }
    }

    /**
     * 发送图片
     */
    fun sendPhoto(chatId: Long, imageData: ByteArray) {
        try {
            val inputStream = ByteArrayInputStream(imageData)
            val photo = SendPhoto.builder()
                .chatId(chatId)
                .photo(InputFile(inputStream, "notification.png"))
                .build()
            telegramClient.execute(photo)
        } catch (e: Exception) {
            log.error("发送图片失败: {}", e.message, e)
        }
    }
}
