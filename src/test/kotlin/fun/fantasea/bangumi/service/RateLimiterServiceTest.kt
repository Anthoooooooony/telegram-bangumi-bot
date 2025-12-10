package `fun`.fantasea.bangumi.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RateLimiterServiceTest {

    @Test
    fun `should allow burst requests`() {
        val service = RateLimiterService(burst = 3, restoreIntervalSeconds = 10)
        val userId = 12345L

        assertTrue(service.tryAcquire(userId), "第1次请求应该被允许")
        assertTrue(service.tryAcquire(userId), "第2次请求应该被允许")
        assertTrue(service.tryAcquire(userId), "第3次请求应该被允许")
        assertFalse(service.tryAcquire(userId), "第4次请求应该被限制")
    }

    @Test
    fun `should track users independently`() {
        val service = RateLimiterService(burst = 2, restoreIntervalSeconds = 10)
        val user1 = 11111L
        val user2 = 22222L

        assertTrue(service.tryAcquire(user1), "用户1第一次请求应该被允许")
        assertTrue(service.tryAcquire(user2), "用户2第一次请求应该被允许")
        assertTrue(service.tryAcquire(user1), "用户1第二次请求应该被允许")
        assertTrue(service.tryAcquire(user2), "用户2第二次请求应该被允许")
        assertFalse(service.tryAcquire(user1), "用户1第三次请求应该被限制")
        assertFalse(service.tryAcquire(user2), "用户2第三次请求应该被限制")
    }
}
