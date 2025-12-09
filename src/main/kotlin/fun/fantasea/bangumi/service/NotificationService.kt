package `fun`.fantasea.bangumi.service

import `fun`.fantasea.bangumi.bot.BangumiBot
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
    @Lazy private val bangumiBot: BangumiBot
) {
    private val log = LoggerFactory.getLogger(NotificationService::class.java)

    /**
     * 发送新剧集通知
     */
    fun sendNewEpisodeNotification(
        telegramId: Long,
        subscription: Subscription,
        episodeNumber: Int,
        episodeName: String?
    ) {
        val animeName = subscription.subjectNameCn?.takeIf { it.isNotBlank() } ?: subscription.subjectName
        val epInfo = episodeName?.takeIf { it.isNotBlank() }?.let { " - $it" } ?: ""

        val message = """
            新剧集更新！

            $animeName
            第 $episodeNumber 集$epInfo
        """.trimIndent()

        log.info("发送新剧集通知: telegramId={}, anime={}, ep={}", telegramId, animeName, episodeNumber)
        bangumiBot.sendMessage(telegramId, message)
    }

    /**
     * 发送每日汇总
     */
    fun sendDailySummary(telegramId: Long, todayAnimes: List<TodayAnimeInfo>) {
        if (todayAnimes.isEmpty()) {
            val message = "今日没有追番更新。"
            bangumiBot.sendMessage(telegramId, message)
            return
        }

        val sb = StringBuilder("今日追番更新 (${todayAnimes.size} 部):\n\n")

        todayAnimes.forEachIndexed { index, anime ->
            val name = anime.nameCn?.takeIf { it.isNotBlank() } ?: anime.name
            sb.append("${index + 1}. $name\n")
        }

        log.info("发送每日汇总: telegramId={}, count={}", telegramId, todayAnimes.size)
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
    val nameCn: String?
)
