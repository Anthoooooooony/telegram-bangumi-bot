package `fun`.fantasea.bangumi.service

import `fun`.fantasea.bangumi.bot.BangumiBot
import `fun`.fantasea.bangumi.client.BangumiClient
import `fun`.fantasea.bangumi.client.BangumiDataClient
import `fun`.fantasea.bangumi.client.PlatformInfo
import `fun`.fantasea.bangumi.entity.Subscription
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service

/**
 * 通知服务
 * 负责向用户发送各类通知
 */
@Service
class NotificationService(
    @param:Lazy private val bangumiBot: BangumiBot,
    private val bangumiDataClient: BangumiDataClient,
    private val bangumiClient: BangumiClient,
    private val imageGeneratorService: ImageGeneratorService
) {
    private val log = LoggerFactory.getLogger(NotificationService::class.java)

    companion object {
        private const val MAX_PLATFORMS = 4
    }

    /**
     * 发送新剧集通知（支持多集聚合）
     * @param episodes 新剧集列表，按集数排序
     */
    suspend fun sendNewEpisodeNotification(
        telegramId: Long,
        subscription: Subscription,
        episodes: List<EpisodeInfo>
    ) {
        if (episodes.isEmpty()) return

        // 使用 Subscription 的便捷方法获取显示名称
        val animeName = subscription.getDisplayName()

        // 生成集数显示文本
        val episodeText = if (episodes.size == 1) {
            "第 ${episodes.first().epNumber} 集"
        } else {
            val epNumbers = episodes.map { it.epNumber }
            "第 ${formatEpisodeRange(epNumbers)} 集"
        }

        // 单集时显示剧集名和播出时间
        val episodeName = if (episodes.size == 1) episodes.first().name else null
        val airTimeDisplay = if (episodes.size == 1) episodes.first().airTimeDisplay else null

        log.info("发送新剧集通知: telegramId={}, anime={}, episodes={}, airTime={}",
            telegramId, animeName, episodes.map { it.epNumber }, airTimeDisplay)

        // 获取封面图（优先从关联的 Anime 获取，回退到 API）
        val coverUrl = subscription.getCoverUrl() ?: try {
            bangumiClient.getSubject(subscription.subjectId).images?.common
        } catch (e: Exception) {
            log.warn("获取封面图失败: subjectId=${subscription.subjectId}, error={}", e.message)
            null
        }

        // 获取播放平台
        val platforms = bangumiDataClient.getPlatforms(subscription.subjectId)
            .filter { it.regions == null || it.regions.contains("CN") }
            .take(MAX_PLATFORMS)

        // 生成图片
        val imageData = try {
            imageGeneratorService.generateNotificationCard(
                animeName = animeName,
                episodeText = episodeText,
                episodeName = episodeName,
                airTimeDisplay = airTimeDisplay,
                coverUrl = coverUrl,
                platforms = platforms
            )
        } catch (e: Exception) {
            throw RuntimeException("生成通知图片失败: subjectId=${subscription.subjectId}", e) // todo 用一个项目自定义的exception，包含属性 userErrorMessage 来指定给用户报错的信息内容
        }

        // 生成播放链接作为图片 caption
        val caption = generatePlatformCaption(platforms)

        // 发送图片（带链接）
        try {
            bangumiBot.sendPhoto(telegramId, imageData, caption)
        } catch (e: Exception) {
            throw RuntimeException("发送通知图片失败: telegramId=$telegramId, subjectId=${subscription.subjectId}", e)
        }
    }

    /**
     * 生成播放平台链接 caption（MarkdownV2 格式）
     * 格式: [Bilibili](url)｜[动画疯](url)｜...
     */
    private fun generatePlatformCaption(platforms: List<PlatformInfo>): String? {
        if (platforms.isEmpty()) return null

        return platforms.joinToString("｜") { platform ->
            // URL 中的特殊字符需要转义
            val escapedUrl = platform.url
                .replace(")", "\\)")
                .replace("(", "\\(")
            "[${escapeMarkdown(platform.name)}](${escapedUrl})"
        }
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

        // 转换为图片生成需要的格式
        val animes = todayAnimes.map { anime ->
            val name = anime.nameCn?.takeIf { it.isNotBlank() } ?: anime.name
            DailySummaryAnime(name, anime.coverUrl, anime.airInfo)
        }

        // 生成图片
        val imageData = try {
            imageGeneratorService.generateDailySummaryCard(animes)
        } catch (e: Exception) {
            throw RuntimeException("生成每日汇总图片失败: telegramId=$telegramId", e)
        }

        // 发送图片
        try {
            bangumiBot.sendPhoto(telegramId, imageData)
        } catch (e: Exception) {
            throw RuntimeException("发送每日汇总图片失败: telegramId=$telegramId", e)
        }
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
    val epNumber: Int,           // 集数（使用 API 的 sort 字段，全局集数）
    val name: String?,           // 剧集名称
    val airTimeDisplay: String? = null  // 播出时间显示，如 "今天 16:35"
)
