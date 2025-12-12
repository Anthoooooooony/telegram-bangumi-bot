package `fun`.fantasea.bangumi.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

/**
 * 任务调度配置
 * 提供 TaskScheduler 用于动态调度通知任务
 */
@Configuration
class SchedulingConfig(
    @param:Value("\${scheduler.pool-size:10}") private val poolSize: Int
) {
    private val log = LoggerFactory.getLogger(SchedulingConfig::class.java)

    @Bean
    fun taskScheduler(): TaskScheduler {
        val scheduler = ThreadPoolTaskScheduler()
        scheduler.poolSize = poolSize
        scheduler.setThreadNamePrefix("notification-scheduler-")
        scheduler.setRejectedExecutionHandler { _, executor ->
            log.warn("通知任务被拒绝，队列已满: executor={}", executor)
        }
        scheduler.setErrorHandler { throwable ->
            log.error("通知任务执行异常: {}", throwable.message, throwable)
        }
        scheduler.initialize()
        log.info("TaskScheduler 初始化完成，线程池大小: {}", poolSize)
        return scheduler
    }
}
