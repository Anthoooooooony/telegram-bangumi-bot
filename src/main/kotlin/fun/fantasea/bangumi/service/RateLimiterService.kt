package `fun`.fantasea.bangumi.service

import com.github.benmanes.caffeine.cache.Caffeine
import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * 用户请求速率限制服务，使用 Bucket4j
 * 策略：初始 burst 个令牌，之后按 restoreInterval 恢复
 */
@Service
class RateLimiterService(
    @param:Value("\${rate-limiter.burst:4}") private val burst: Long,
    @param:Value("\${rate-limiter.restore-interval-seconds:2}") private val restoreIntervalSeconds: Long
) {
    private val log = LoggerFactory.getLogger(RateLimiterService::class.java)

    // 闲置 10 分钟后自动过期，避免内存泄漏
    private val userBuckets = Caffeine.newBuilder()
        .expireAfterAccess(Duration.ofMinutes(10))
        .build<Long, Bucket> { createBucket() }

    private fun createBucket(): Bucket {
        val bandwidth = Bandwidth.builder()
            .capacity(burst)
            .refillGreedy(1, Duration.ofSeconds(restoreIntervalSeconds))
            .build()
        return Bucket.builder().addLimit(bandwidth).build()
    }

    /**
     * 尝试获取许可（非阻塞）
     * @return true 允许请求，false 被限流
     */
    fun tryAcquire(userId: Long): Boolean {
        val bucket = userBuckets.get(userId)!!
        val acquired = bucket.tryConsume(1)
        if (!acquired) {
            log.debug("用户 {} 触发速率限制", userId)
        }
        return acquired
    }
}
