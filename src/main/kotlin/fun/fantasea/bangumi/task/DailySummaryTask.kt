package `fun`.fantasea.bangumi.task

import `fun`.fantasea.bangumi.client.BangumiClient
import `fun`.fantasea.bangumi.repository.SubscriptionRepository
import `fun`.fantasea.bangumi.repository.UserRepository
import `fun`.fantasea.bangumi.repository.UserSpecifications
import `fun`.fantasea.bangumi.service.NotificationService
import `fun`.fantasea.bangumi.service.TodayAnimeInfo
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * 每日汇总定时任务
 * 每分钟检查是否到了用户设定的汇总时间
 */
@Component
class DailySummaryTask(
    private val userRepository: UserRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val bangumiClient: BangumiClient,
    private val notificationService: NotificationService,
    @param:Value("\${anime.timezone:Asia/Shanghai}") private val timezone: String
) {
    private val log = LoggerFactory.getLogger(DailySummaryTask::class.java)

    private val zoneId: ZoneId by lazy { ZoneId.of(timezone) }

    // 记录今日已发送汇总的用户，避免重复发送（线程安全）
    private val sentToday: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val lastResetDate = AtomicReference(LocalDate.now(zoneId))

    /**
     * 每分钟检查是否需要发送每日汇总
     */
    @Scheduled(cron = "0 * * * * *") // 每分钟执行
    fun checkAndSendDailySummary() {
        val now = LocalTime.now(zoneId)
        val today = LocalDate.now(zoneId)

        // 如果日期变化，重置已发送列表（使用 CAS 保证线程安全）
        val previousDate = lastResetDate.get()
        if (today != previousDate && lastResetDate.compareAndSet(previousDate, today)) {
            sentToday.clear()
        }

        // 获取所有启用每日汇总且满足条件的用户
        val spec = UserSpecifications.dailySummaryEnabled()
            .and(UserSpecifications.hasBangumiToken())
            .and(UserSpecifications.idNotIn(sentToday))
            .and(UserSpecifications.dailySummaryTimeMatches(now))
        val users = userRepository.findAll(spec)

        if (users.isEmpty()) return

        log.info("准备发送每日汇总给 {} 位用户", users.size)

        runBlocking {
            try {
                // 获取今日放送表
                val calendar = bangumiClient.getCalendar()
                val todayWeekday = today.dayOfWeek.value
                val todayItems = calendar.find { it.weekday.id == todayWeekday }?.items ?: emptyList()

                for (user in users) {
                    try {
                        sendSummaryToUser(user.telegramId, todayItems.map { it.id }.toSet(), user.dailySummaryTime)
                        sentToday.add(user.id!!)
                    } catch (e: Exception) {
                        log.warn("发送每日汇总失败: telegramId={}, error={}", user.telegramId, e.message)
                    }
                }
            } catch (e: Exception) {
                log.error("获取放送表失败: {}", e.message, e)
            }
        }
    }

    /**
     * 发送汇总给用户
     * @param summaryTime 用户配置的汇总时间，用于计算 24h 时间窗口
     */
    private suspend fun sendSummaryToUser(telegramId: Long, todayAiringIds: Set<Int>, summaryTime: LocalTime) { // todo 确定具体播送时间
        // 获取用户的订阅
        val subscriptions = subscriptionRepository.findByUserTelegramId(telegramId)

        // 筛选出今日有更新的订阅
        val todaySubscriptions = subscriptions.filter { it.subjectId in todayAiringIds }

        // 计算 24h 时间窗口的日期范围
        // 汇总时间窗口: (今天 summaryTime - 24h) 到 (今天 summaryTime)
        // 由于 airdate 只有日期没有时间，我们取窗口覆盖的两个日期
        val today = LocalDate.now(zoneId)
        val yesterday = today.minusDays(1)
        val validDates = setOf(yesterday.toString(), today.toString())

        // 获取封面图和剧集信息
        val todayAnimes = todaySubscriptions.mapNotNull { sub ->
            var coverUrl: String? = null
            var airInfo: String? = null
            var hasRecentEpisode = false

            try {
                // 获取封面
                coverUrl = bangumiClient.getSubject(sub.subjectId).images?.common

                // 获取剧集信息，找出 24h 窗口内更新的集数
                val episodes = bangumiClient.getEpisodes(sub.subjectId)
                val recentEpisode = episodes.data
                    .filter { it.type == 0 }  // 本篇
                    .filter { it.airdate in validDates }
                    .maxByOrNull { it.sort }

                if (recentEpisode != null) {
                    hasRecentEpisode = true
                    val epNum = recentEpisode.sort.toInt()
                    airInfo = "第 $epNum 集"
                }
            } catch (e: Exception) {
                log.debug("获取番剧信息失败: subjectId={}, error={}", sub.subjectId, e.message)
            }

            // 只返回在 24h 窗口内有更新的番剧
            if (hasRecentEpisode) {
                TodayAnimeInfo(sub.subjectId, sub.subjectName, sub.subjectNameCn, coverUrl, airInfo)
            } else {
                null
            }
        }

        if (todayAnimes.isNotEmpty()) {
            notificationService.sendDailySummary(telegramId, todayAnimes)
            log.info("已发送每日汇总: telegramId={}, count={}", telegramId, todayAnimes.size)
        } else {
            log.debug("用户 {} 在 24h 窗口内无更新，跳过汇总", telegramId)
        }
    }
}
