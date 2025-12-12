package `fun`.fantasea.bangumi.bot

import `fun`.fantasea.bangumi.client.BangumiClient
import `fun`.fantasea.bangumi.service.BangumiCacheService
import `fun`.fantasea.bangumi.service.ImageGeneratorService
import `fun`.fantasea.bangumi.service.RateLimiterService
import `fun`.fantasea.bangumi.service.SubscriptionAnime
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
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.api.objects.InputFile
import java.io.ByteArrayInputStream
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand
import org.telegram.telegrambots.meta.generics.TelegramClient
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.time.LocalDate
import java.util.concurrent.TimeUnit

@Component
class BangumiBot(
    @Value("\${telegram.bot.token}") private val botToken: String,
    @Value("\${telegram.proxy.host:}") private val proxyHost: String,
    @Value("\${telegram.proxy.port:0}") private val proxyPort: Int,
    private val userService: UserService,
    private val subscriptionService: SubscriptionService,
    private val imageGeneratorService: ImageGeneratorService,
    private val bangumiClient: BangumiClient,
    private val bangumiCacheService: BangumiCacheService,
    private val rateLimiterService: RateLimiterService
) : SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private val log = LoggerFactory.getLogger(BangumiBot::class.java)
    private val telegramClient: TelegramClient = createTelegramClient()

    private fun createTelegramClient(): TelegramClient {
        if (proxyHost.isNotBlank() && proxyPort > 0) {
            log.info("使用代理连接 Telegram: {}:{}", proxyHost, proxyPort)
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort))
            val okHttpClient = OkHttpClient.Builder()
                .proxy(proxy)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
            return OkHttpTelegramClient(okHttpClient, botToken)
        }
        return OkHttpTelegramClient(botToken)
    }
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
            BotCommand("list", "显示追番列表"),
            BotCommand("status", "查看绑定状态"),
            BotCommand("unbind", "解除绑定")
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

            // 速率限制检查
            if (!rateLimiterService.tryAcquire(userId)) {
                sendMessage(chatId, "请求太频繁，请稍后再试。")
                return
            }

            // 确保用户存在
            userService.getOrCreateUser(userId, username)

            when {
                text.startsWith("/start") -> handleStart(chatId, username)
                text.startsWith("/bindtoken") -> handleBindToken(chatId, userId, text)
                text.startsWith("/unbind") -> handleUnbind(chatId, userId)
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
            /bindtoken <token> - 绑定 Bangumi token（自动同步追番列表）
            /unbind - 解除绑定
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
        val messageId = sendMessageAndGetId(chatId, "正在验证 Token...")

        scope.launch {
            val result = userService.bindToken(userId, token)
            if (result.success) {
                editMessage(chatId, messageId, """
                    绑定成功！
                    Bangumi 用户: ${result.bangumiUsername}
                    昵称: ${result.bangumiNickname}

                    正在同步追番列表...
                """.trimIndent())

                // 自动同步追番列表
                val syncResult = subscriptionService.syncSubscriptions(userId)
                if (syncResult.success) {
                    editMessage(chatId, messageId, """
                        绑定成功！
                        Bangumi 用户: ${result.bangumiUsername}
                        昵称: ${result.bangumiNickname}

                        同步完成！共 ${syncResult.syncedCount} 部在看。
                        使用 /list 查看追番列表。
                    """.trimIndent())
                } else {
                    editMessage(chatId, messageId, """
                        绑定成功！
                        Bangumi 用户: ${result.bangumiUsername}
                        昵称: ${result.bangumiNickname}

                        同步失败: ${syncResult.error}
                        请稍后手动重试。
                    """.trimIndent())
                }
            } else {
                editMessage(chatId, messageId, "绑定失败: ${result.error}")
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

    private fun handleList(chatId: Long, userId: Long) {
        val subscriptions = subscriptionService.getUserSubscriptions(userId)

        if (subscriptions.isEmpty()) {
            sendMessage(chatId, "追番列表为空。\n\n请先使用 /bindtoken 绑定 Bangumi 账号。")
            return
        }

        val messageId = sendMessageAndGetId(chatId, "正在生成追番列表...")

        scope.launch {
            try {
                val today = LocalDate.now()
                // 获取每个订阅的封面图和当前已播出集数（使用缓存）
                val animes = subscriptions.map { sub ->
                    val name = sub.subjectNameCn?.takeIf { it.isNotBlank() } ?: sub.subjectName
                    var coverUrl: String? = null
                    var latestAiredEp: Int? = null

                    try {
                        // 优先从缓存获取番剧详情
                        val subject = bangumiCacheService.getSubject(sub.subjectId)
                            ?: bangumiClient.getSubject(sub.subjectId).also {
                                bangumiCacheService.putSubject(sub.subjectId, it)
                            }
                        coverUrl = subject.images?.common

                        // 优先从缓存获取剧集列表
                        val episodes = bangumiCacheService.getEpisodes(sub.subjectId)
                            ?: bangumiClient.getEpisodes(sub.subjectId).also {
                                bangumiCacheService.putEpisodes(sub.subjectId, it)
                            }
                        latestAiredEp = episodes.data
                            .filter { it.type == 0 }
                            .filter { ep ->
                                val airdate = ep.airdate ?: return@filter false
                                try {
                                    !LocalDate.parse(airdate).isAfter(today)
                                } catch (e: Exception) { false }
                            }
                            .maxOfOrNull { it.ep?.toInt() ?: it.sort.toInt() }
                    } catch (e: Exception) {
                        log.debug("获取番剧信息失败: subjectId={}", sub.subjectId)
                    }

                    SubscriptionAnime(
                        name = name,
                        coverUrl = coverUrl,
                        totalEpisodes = sub.totalEpisodes,
                        latestAiredEp = latestAiredEp
                    )
                }

                val imageData = imageGeneratorService.generateSubscriptionListCard(animes)
                deleteMessage(chatId, messageId)
                sendPhoto(chatId, imageData)
            } catch (e: Exception) {
                log.error("生成追番列表图片失败: {}", e.message, e)
                // 降级为文本输出
                val sb = StringBuilder("追番列表 (${subscriptions.size} 部):\n\n")
                subscriptions.forEachIndexed { index, sub ->
                    val name = sub.subjectNameCn?.takeIf { it.isNotBlank() } ?: sub.subjectName
                    val eps = sub.totalEpisodes?.let { " (${it}集)" } ?: ""
                    sb.append("${index + 1}. $name$eps\n")
                }
                editMessage(chatId, messageId, sb.toString())
            }
        }
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
     * 发送消息并返回消息 ID，用于后续编辑
     */
    private fun sendMessageAndGetId(chatId: Long, text: String): Int {
        return try {
            val message = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .build()
            telegramClient.execute(message).messageId
        } catch (e: Exception) {
            log.error("发送消息失败: {}", e.message, e)
            -1
        }
    }

    /**
     * 编辑已发送的消息
     */
    private fun editMessage(chatId: Long, messageId: Int, text: String) {
        if (messageId < 0) {
            // 消息发送失败，改为发送新消息
            sendMessage(chatId, text)
            return
        }
        try {
            val edit = EditMessageText.builder()
                .chatId(chatId)
                .messageId(messageId)
                .text(text)
                .build()
            telegramClient.execute(edit)
        } catch (e: Exception) {
            log.error("编辑消息失败: {}", e.message, e)
        }
    }

    /**
     * 删除已发送的消息
     */
    private fun deleteMessage(chatId: Long, messageId: Int) {
        if (messageId < 0) return
        try {
            val delete = DeleteMessage.builder()
                .chatId(chatId)
                .messageId(messageId)
                .build()
            telegramClient.execute(delete)
        } catch (e: Exception) {
            log.debug("删除消息失败: {}", e.message)
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
     * @param caption 图片说明文字（可选，支持 MarkdownV2 格式）
     */
    fun sendPhoto(chatId: Long, imageData: ByteArray, caption: String? = null) {
        try {
            val inputStream = ByteArrayInputStream(imageData)
            val photoBuilder = SendPhoto.builder()
                .chatId(chatId)
                .photo(InputFile(inputStream, "notification.png"))

            if (!caption.isNullOrBlank()) {
                photoBuilder.caption(caption)
                photoBuilder.parseMode("MarkdownV2")
            }

            telegramClient.execute(photoBuilder.build())
        } catch (e: Exception) {
            log.error("发送图片失败: {}", e.message, e)
        }
    }
}
