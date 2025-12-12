package `fun`.fantasea.bangumi.service

import `fun`.fantasea.bangumi.entity.Anime
import `fun`.fantasea.bangumi.entity.Subscription
import `fun`.fantasea.bangumi.repository.SubscriptionRepository
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.scheduling.TaskScheduler
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
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
    private val transactionTemplate: TransactionTemplate,
    @param:Lazy private val notificationService: NotificationService,
    private val animeService: AnimeService
) {
    private val log = LoggerFactory.getLogger(ScheduledNotificationService::class.java)

    // 存储订阅 ID -> ScheduledFuture 的映射，用于取消任务
    private val scheduledTasks = ConcurrentHashMap<Long, ScheduledFuture<*>>()

    // 协程异常处理器，作为最后一道防线
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        log.error("协程未捕获异常: {}", throwable.message, throwable)
    }

    // 使用 SupervisorJob 防止单个任务失败影响其他任务
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)

    @PreDestroy
    fun shutdown() {
        log.info("关闭通知调度服务，取消 {} 个待执行任务", scheduledTasks.size)
        scope.cancel()
        scheduledTasks.values.forEach { it.cancel(false) }
        scheduledTasks.clear()
    }

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
        val now = Instant.now()

        // 更新数据库
        subscription.nextNotifyTime = airTime
        subscription.nextNotifyEp = nextEp
        subscriptionRepository.save(subscription)

        // 如果播出时间已过，不安排任务（但保留数据库记录供重启恢复检查）
        if (airTime.isBefore(now)) {
            log.debug("播出时间已过，跳过调度: {} 第{}集 于 {}", anime.name, nextEp, airTime)
            scheduledTasks.remove(subscriptionId)?.cancel(false)
            return
        }

        // 原子操作：取消旧任务并安排新任务
        scheduledTasks.compute(subscriptionId) { _, oldFuture ->
            oldFuture?.cancel(false)
            taskScheduler.schedule({ executeNotification(subscriptionId) }, airTime)
        }

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
     * 执行通知（在协程中异步执行，不阻塞调度线程池）
     */
    private fun executeNotification(subscriptionId: Long) {
        scope.launch {
            executeNotificationAsync(subscriptionId)
        }
    }

    /**
     * 异步执行通知逻辑
     */
    private suspend fun executeNotificationAsync(subscriptionId: Long) {
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
            val nextEp = subscription.nextNotifyEp
            if (nextEp == null) {
                log.warn("订阅无下一集信息: subscriptionId={}, subjectId={}", subscriptionId, subjectId)
                scheduledTasks.remove(subscriptionId)
                return
            }

            // 发送通知 - 失败则不更新数据库，保持任务状态
            try {
                notificationService.sendNewEpisodeNotification(
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
                log.error("发送通知失败，保持任务状态等待重试: subscriptionId={}, subjectId={}, anime={}, error={}",
                    subscriptionId, subjectId, animeName, e.message, e)
                return
            }

            // 通知成功后更新数据库（使用事务确保原子性）
            scheduledTasks.remove(subscriptionId)
            transactionTemplate.execute {
                subscription.lastNotifiedEp = nextEp
                subscription.latestAiredEp = nextEp
                subscription.nextNotifyTime = null
                subscription.nextNotifyEp = null
                subscriptionRepository.save(subscription)
            }

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
