package `fun`.fantasea.bangumi.client

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Bangumi API 连接测试
 * 调用公开 API 验证连接正常
 */
class BangumiClientTest {

    private val client = BangumiClient(
        baseUrl = "https://api.bgm.tv",
        userAgent = "telegram-bangumi-bot/1.0 (test)"
    )

    @Test
    fun `should get calendar from bangumi api`() = runBlocking {
        // when
        val calendar = client.getCalendar()

        // then
        assertEquals(7, calendar.size, "应该有7天的放送表")
        assertTrue(calendar.all { it.weekday.id in 1..7 }, "weekday id 应该在 1-7 之间")
    }
}
