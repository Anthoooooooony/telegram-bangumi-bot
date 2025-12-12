package `fun`.fantasea.bangumi.task

import `fun`.fantasea.bangumi.repository.SubscriptionRepository
import `fun`.fantasea.bangumi.service.ScheduledNotificationService
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * 通知调度器初始化组件
 * 应用启动时恢复所有待调度的通知任务
 */
@Component
class NotificationSchedulerInitializer(
    private val subscriptionRepository: SubscriptionRepository,
    private val scheduledNotificationService: ScheduledNotificationService
) {
    private val log = LoggerFactory.getLogger(NotificationSchedulerInitializer::class.java)

    /**
     * 应用启动完成后恢复通知调度
     */
    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        log.info("开始恢复通知调度任务...")

        val subscriptionsToRecover = subscriptionRepository.findAllWithNextNotifyTime()
        if (subscriptionsToRecover.isEmpty()) {
            log.info("无待恢复的通知调度任务")
            return
        }

        val now = Instant.now()
        var futureCount = 0
        var pastCount = 0

        for (subscription in subscriptionsToRecover) {
            val nextNotifyTime = subscription.nextNotifyTime ?: continue
            val anime = subscription.anime

            if (anime == null || !anime.hasBangumiData) {
                // 清理无效的调度记录
                subscription.nextNotifyTime = null
                subscription.nextNotifyEp = null
                subscriptionRepository.save(subscription)
                continue
            }

            if (nextNotifyTime.isAfter(now)) futureCount++ else pastCount++
            scheduledNotificationService.scheduleNextNotification(subscription)
        }

        log.info("通知调度恢复完成: 未来任务={}, 过期任务={}, 当前调度数={}",
            futureCount, pastCount, scheduledNotificationService.getScheduledTaskCount())
    }
}
