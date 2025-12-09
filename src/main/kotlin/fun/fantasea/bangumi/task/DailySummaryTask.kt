package `fun`.fantasea.bangumi.task

import `fun`.fantasea.bangumi.client.BangumiClient
import `fun`.fantasea.bangumi.repository.SubscriptionRepository
import `fun`.fantasea.bangumi.repository.UserRepository
import `fun`.fantasea.bangumi.service.NotificationService
import `fun`.fantasea.bangumi.service.TodayAnimeInfo
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.LocalTime
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
    private val notificationService: NotificationService
) {
    private val log = LoggerFactory.getLogger(DailySummaryTask::class.java)

    // 记录今日已发送汇总的用户，避免重复发送（线程安全）
    private val sentToday: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val lastResetDate = AtomicReference(LocalDate.now())

    /**
     * 每分钟检查是否需要发送每日汇总
     */
    @Scheduled(cron = "0 * * * * *") // 每分钟执行
    fun checkAndSendDailySummary() {
        val now = LocalTime.now()
        val today = LocalDate.now()

        // 如果日期变化，重置已发送列表（使用 CAS 保证线程安全）
        val previousDate = lastResetDate.get()
        if (today != previousDate && lastResetDate.compareAndSet(previousDate, today)) {
            sentToday.clear()
        }

        // 获取所有启用每日汇总的用户
        val users = userRepository.findAll()
            .filter { it.dailySummaryEnabled }
            .filter { it.bangumiToken != null }
            .filter { it.id !in sentToday }
            .filter { isTimeMatch(now, it.dailySummaryTime) }

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
                        sendSummaryToUser(user.telegramId, todayItems.map { it.id }.toSet())
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

    private suspend fun sendSummaryToUser(telegramId: Long, todayAiringIds: Set<Int>) {
        // 获取用户的订阅
        val subscriptions = subscriptionRepository.findByUserTelegramId(telegramId)

        // 筛选出今日有更新的订阅
        val todaySubscriptions = subscriptions.filter { it.subjectId in todayAiringIds }
        val today = LocalDate.now()

        // 获取封面图和剧集信息
        val todayAnimes = todaySubscriptions.map { sub ->
            var coverUrl: String? = null
            var airInfo: String? = null

            try {
                // 获取封面
                coverUrl = bangumiClient.getSubject(sub.subjectId).images?.common

                // 获取剧集信息，找出今日更新的集数
                val episodes = bangumiClient.getEpisodes(sub.subjectId)
                val todayEpisode = episodes.data
                    .filter { it.type == 0 }  // 本篇
                    .filter { it.airdate == today.toString() }
                    .maxByOrNull { it.sort }

                if (todayEpisode != null) {
                    val epNum = todayEpisode.ep?.toInt() ?: todayEpisode.sort.toInt()
                    airInfo = "第 $epNum 集"
                }
            } catch (e: Exception) {
                log.debug("获取番剧信息失败: subjectId={}, error={}", sub.subjectId, e.message)
            }

            TodayAnimeInfo(sub.subjectId, sub.subjectName, sub.subjectNameCn, coverUrl, airInfo)
        }

        notificationService.sendDailySummary(telegramId, todayAnimes)
        log.info("已发送每日汇总: telegramId={}, count={}", telegramId, todayAnimes.size)
    }

    /**
     * 检查当前时间是否与设定时间匹配（精确到分钟）
     */
    private fun isTimeMatch(now: LocalTime, target: LocalTime): Boolean {
        return now.hour == target.hour && now.minute == target.minute
    }
}
