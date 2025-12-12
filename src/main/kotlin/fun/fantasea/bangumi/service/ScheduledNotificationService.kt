package `fun`.fantasea.bangumi.service

import `fun`.fantasea.bangumi.entity.Anime
import `fun`.fantasea.bangumi.entity.Subscription
import `fun`.fantasea.bangumi.repository.SubscriptionRepository
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.scheduling.TaskScheduler
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture

/**
 * 定时通知调度服务
 * 为有精确时间数据的番剧安排精确播出时间的通知
 */
@Service
class ScheduledNotificationService(
    private val taskScheduler: TaskScheduler,
    private val subscriptionRepository: SubscriptionRepository,
    @param:Lazy private val notificationService: NotificationService,
    private val animeService: AnimeService
) {
    private val log = LoggerFactory.getLogger(ScheduledNotificationService::class.java)

    // 存储订阅 ID -> ScheduledFuture 的映射，用于取消任务
    private val scheduledTasks = ConcurrentHashMap<Long, ScheduledFuture<*>>()

    /**
     * 为订阅安排下一集的通知
     * 仅处理有 BangumiData 时间信息的番剧
     */
    fun scheduleNextNotification(subscription: Subscription) {
        val anime = subscription.anime
        if (anime == null) {
            log.debug("订阅 {} 无关联番剧", subscription.id)
            return
        }

        // 仅处理有 BangumiData 时间信息的番剧
        if (!anime.hasBangumiData) {
            log.debug("跳过无 BangumiData 时间信息的番剧: {}", anime.name)
            return
        }

        val nextEp = subscription.lastNotifiedEp + 1
        val airTime = calculateNextAirTime(anime, nextEp) ?: return

        val subscriptionId = subscription.id!!

        // 取消已存在的任务
        cancelScheduledTask(subscriptionId)

        // 更新数据库
        subscription.nextNotifyTime = airTime
        subscription.nextNotifyEp = nextEp
        subscriptionRepository.save(subscription)

        // 如果播出时间已过，不安排任务
        val now = Instant.now()
        if (airTime.isBefore(now)) {
            log.debug("播出时间已过，跳过调度: {} 第{}集 于 {}", anime.name, nextEp, airTime)
            return
        }

        // 安排任务
        val future = taskScheduler.schedule(
            { executeNotification(subscriptionId) },
            airTime
        )
        scheduledTasks[subscriptionId] = future

        log.info("已安排通知: {} 第{}集 于 {} (用户 {})",
            animeService.getDisplayName(anime), nextEp, airTime, subscription.user.telegramId)
    }

    /**
     * 计算下一集播出时间，包含终止条件检查
     * @return 播出时间，如果应停止调度则返回 null
     */
    private fun calculateNextAirTime(anime: Anime, nextEp: Int): Instant? {
        val displayName = animeService.getDisplayName(anime)

        // 检查是否已超过总集数
        val totalEpisodes = anime.totalEpisodes
        if (totalEpisodes != null && nextEp > totalEpisodes) {
            log.info("番剧已完结，停止调度: {} (subjectId={}, 总集数={})",
                displayName, anime.subjectId, totalEpisodes)
            return null
        }

        val airTime = animeService.calculateEpisodeAirTime(anime, nextEp)
        if (airTime == null) {
            log.debug("无法计算下一集播出时间: {} 第{}集", displayName, nextEp)
            return null
        }

        // 检查是否已超过结束时间
        val endTime = anime.endTime
        if (endTime != null && airTime.isAfter(endTime)) {
            log.info("播出时间超过结束时间，停止调度: {} (subjectId={}, airTime={}, endTime={})",
                displayName, anime.subjectId, airTime, endTime)
            return null
        }

        return airTime
    }

    /**
     * 取消订阅的定时任务（仅内存）
     */
    fun cancelScheduledTask(subscriptionId: Long) {
        scheduledTasks.remove(subscriptionId)?.cancel(false)
    }

    /**
     * 取消订阅的定时任务并清理数据库
     * 用于取消订阅或删除订阅时调用，确保重启后不会重新调度已取消的任务
     */
    fun cancelAndClearScheduledTask(subscriptionId: Long) {
        scheduledTasks.remove(subscriptionId)?.cancel(false)
        subscriptionRepository.findById(subscriptionId).ifPresent { subscription ->
            if (subscription.nextNotifyTime != null) {
                subscription.nextNotifyTime = null
                subscription.nextNotifyEp = null
                subscriptionRepository.save(subscription)
                log.info("已取消并清理订阅 {} 的调度信息", subscriptionId)
            }
        }
    }

    /**
     * 执行通知
     */
    private fun executeNotification(subscriptionId: Long) {
        var subjectId: Int? = null
        var animeName: String? = null

        try {
            val subscription = subscriptionRepository.findById(subscriptionId).orElse(null)
            if (subscription == null) {
                log.warn("执行通知时订阅不存在: subscriptionId={}", subscriptionId)
                scheduledTasks.remove(subscriptionId)
                return
            }

            subjectId = subscription.subjectId
            val anime = subscription.anime
            if (anime == null) {
                log.warn("订阅无关联番剧: subscriptionId={}, subjectId={}", subscriptionId, subjectId)
                scheduledTasks.remove(subscriptionId)
                return
            }

            animeName = animeService.getDisplayName(anime)
            val nextEp = subscription.nextNotifyEp ?: return

            // 发送通知
            runBlocking {
                try {
                    notificationService.sendNewEpisodeNotification( // todo 改为一次性向所有 user 发送 而不是一个一个来
                        telegramId = subscription.user.telegramId,
                        subscription = subscription,
                        episodes = listOf(EpisodeInfo(
                            epNumber = nextEp,
                            name = null,
                            airTimeDisplay = animeService.formatAirTime(anime, nextEp)
                        ))
                    )

                    log.info("已发送通知: {} 第{}集 (用户 {}, subjectId={})",
                        animeName, nextEp, subscription.user.telegramId, subjectId)
                } catch (e: Exception) {
                    log.error("发送通知失败: subscriptionId={}, subjectId={}, anime={}, error={}",
                        subscriptionId, subjectId, animeName, e.message, e)
                }
            }

            // 更新已通知集数
            subscription.lastNotifiedEp = nextEp
            subscription.latestAiredEp = nextEp

            // 清除当前调度信息
            scheduledTasks.remove(subscriptionId)
            subscription.nextNotifyTime = null
            subscription.nextNotifyEp = null
            subscriptionRepository.save(subscription)

            // 安排下一集通知
            scheduleNextNotification(subscription)

        } catch (e: Exception) {
            log.error("执行通知任务异常: subscriptionId={}, subjectId={}, anime={}",
                subscriptionId, subjectId, animeName, e)
            scheduledTasks.remove(subscriptionId)
        }
    }

    /**
     * 获取当前调度的任务数量（用于监控）
     */
    fun getScheduledTaskCount(): Int = scheduledTasks.size

    /**
     * 获取所有已调度的订阅 ID
     */
    fun getScheduledSubscriptionIds(): Set<Long> = scheduledTasks.keys.toSet()
}
