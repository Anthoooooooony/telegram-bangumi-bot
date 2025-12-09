package `fun`.fantasea.bangumi.service

import `fun`.fantasea.bangumi.bot.BangumiBot
import `fun`.fantasea.bangumi.client.BangumiClient
import `fun`.fantasea.bangumi.client.BangumiDataClient
import `fun`.fantasea.bangumi.entity.Subscription
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service

/**
 * 通知服务
 * 负责向用户发送各类通知
 */
@Service
class NotificationService(
    @Lazy private val bangumiBot: BangumiBot,
    private val bangumiDataClient: BangumiDataClient,
    private val bangumiClient: BangumiClient,
    private val imageGeneratorService: ImageGeneratorService
) {
    private val log = LoggerFactory.getLogger(NotificationService::class.java)

    /**
     * 发送新剧集通知（支持多集聚合）
     * @param episodes 新剧集列表，按集数排序
     */
    fun sendNewEpisodeNotification(
        telegramId: Long,
        subscription: Subscription,
        episodes: List<EpisodeInfo>
    ) {
        if (episodes.isEmpty()) return

        val animeName = subscription.subjectNameCn?.takeIf { it.isNotBlank() } ?: subscription.subjectName

        // 生成集数显示文本
        val episodeText = if (episodes.size == 1) {
            "第 ${episodes.first().epNumber} 集"
        } else {
            val epNumbers = episodes.map { it.epNumber }
            "第 ${formatEpisodeRange(epNumbers)} 集"
        }

        // 单集时显示剧集名
        val episodeName = if (episodes.size == 1) episodes.first().name else null

        log.info("发送新剧集通知: telegramId={}, anime={}, episodes={}",
            telegramId, animeName, episodes.map { it.epNumber })

        try {
            // 获取封面图
            val coverUrl = runBlocking {
                try {
                    bangumiClient.getSubject(subscription.subjectId).images?.common
                } catch (e: Exception) {
                    log.warn("获取封面图失败: {}", e.message)
                    null
                }
            }

            // 获取播放平台
            val platforms = bangumiDataClient.getPlatforms(subscription.subjectId)
                .filter { it.regions == null || it.regions.contains("CN") }
                .take(4)

            // 生成图片
            val imageData = imageGeneratorService.generateNotificationCard(
                animeName = animeName,
                episodeText = episodeText,
                episodeName = episodeName,
                coverUrl = coverUrl,
                platforms = platforms
            )

            // 发送图片
            bangumiBot.sendPhoto(telegramId, imageData)
        } catch (e: Exception) {
            log.error("生成通知图片失败，降级为文字通知: {}", e.message)
            // 降级为文字通知
            sendTextNotification(telegramId, animeName, episodeText, episodeName, subscription.subjectId)
        }
    }

    /**
     * 发送文字通知（降级方案）
     */
    private fun sendTextNotification(
        telegramId: Long,
        animeName: String,
        episodeText: String,
        episodeName: String?,
        subjectId: Int
    ) {
        val platformLinks = bangumiDataClient.generatePlatformLinks(subjectId)
        val linksLine = platformLinks?.let { "\n\n直达链接：$it" } ?: ""

        val safeAnimeName = escapeMarkdown(animeName)
        val epInfo = episodeName?.takeIf { it.isNotBlank() }?.let { " \\- ${escapeMarkdown(it)}" } ?: ""

        val message = """
            *新剧集更新！*

            $safeAnimeName
            $episodeText$epInfo$linksLine
        """.trimIndent()

        bangumiBot.sendMessageMarkdown(telegramId, message)
    }

    /**
     * 格式化集数范围显示
     * 连续集数用范围（如 5-8），非连续用逗号（如 5、7、9）
     */
    private fun formatEpisodeRange(episodes: List<Int>): String {
        if (episodes.isEmpty()) return ""
        if (episodes.size == 1) return episodes.first().toString()

        val sorted = episodes.sorted()
        val ranges = mutableListOf<String>()
        var start = sorted.first()
        var end = start

        for (i in 1 until sorted.size) {
            if (sorted[i] == end + 1) {
                end = sorted[i]
            } else {
                ranges.add(if (start == end) "$start" else "$start\\-$end")
                start = sorted[i]
                end = start
            }
        }
        ranges.add(if (start == end) "$start" else "$start\\-$end")

        return ranges.joinToString("、")
    }

    /**
     * 转义 Markdown 特殊字符
     * 注意：链接中的字符不需要转义，所以只对文本内容调用此方法
     */
    private fun escapeMarkdown(text: String): String {
        // Telegram Markdown 需要转义的字符: _ * [ ] ( ) ~ ` > # + - = | { } . !
        return text
            .replace("_", "\\_")
            .replace("*", "\\*")
            .replace("[", "\\[")
            .replace("]", "\\]")
            .replace("(", "\\(")
            .replace(")", "\\)")
            .replace("~", "\\~")
            .replace("`", "\\`")
            .replace(">", "\\>")
            .replace("#", "\\#")
            .replace("+", "\\+")
            .replace("-", "\\-")
            .replace("=", "\\=")
            .replace("|", "\\|")
            .replace("{", "\\{")
            .replace("}", "\\}")
            .replace(".", "\\.")
            .replace("!", "\\!")
    }

    /**
     * 发送每日汇总
     */
    fun sendDailySummary(telegramId: Long, todayAnimes: List<TodayAnimeInfo>) {
        if (todayAnimes.isEmpty()) {
            bangumiBot.sendMessage(telegramId, "今日没有追番更新。")
            return
        }

        log.info("发送每日汇总: telegramId={}, count={}", telegramId, todayAnimes.size)

        try {
            // 转换为图片生成需要的格式
            val animes = todayAnimes.map { anime ->
                val name = anime.nameCn?.takeIf { it.isNotBlank() } ?: anime.name
                DailySummaryAnime(name, anime.coverUrl, anime.airInfo)
            }

            // 生成图片
            val imageData = imageGeneratorService.generateDailySummaryCard(animes)

            // 发送图片
            bangumiBot.sendPhoto(telegramId, imageData)
        } catch (e: Exception) {
            log.error("生成每日汇总图片失败，降级为文字通知: {}", e.message)
            // 降级为文字通知
            sendDailySummaryText(telegramId, todayAnimes)
        }
    }

    /**
     * 发送文字版每日汇总（降级方案）
     */
    private fun sendDailySummaryText(telegramId: Long, todayAnimes: List<TodayAnimeInfo>) {
        val sb = StringBuilder("今日追番更新 (${todayAnimes.size} 部):\n\n")
        todayAnimes.forEachIndexed { index, anime ->
            val name = anime.nameCn?.takeIf { it.isNotBlank() } ?: anime.name
            sb.append("${index + 1}. $name\n")
        }
        bangumiBot.sendMessage(telegramId, sb.toString())
    }

    /**
     * 发送通用消息
     */
    fun sendMessage(telegramId: Long, message: String) {
        bangumiBot.sendMessage(telegramId, message)
    }
}

/**
 * 今日放送动画信息
 */
data class TodayAnimeInfo(
    val subjectId: Int,
    val name: String,
    val nameCn: String?,
    val coverUrl: String? = null,
    val airInfo: String? = null  // 更新信息，如 "第 5 集"
)

/**
 * 剧集信息（用于通知）
 */
data class EpisodeInfo(
    val epNumber: Int,      // 本季集数（显示用）
    val sortNumber: Int,    // 全局集数（比较用）
    val name: String?       // 剧集名称
)
