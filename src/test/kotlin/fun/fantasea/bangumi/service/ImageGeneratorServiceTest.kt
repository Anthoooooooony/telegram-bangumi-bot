package `fun`.fantasea.bangumi.service

import io.mockk.mockk
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.io.File

/**
 * ImageGeneratorService 测试
 * 生成样例图片用于验证效果
 */
class ImageGeneratorServiceTest {

    private val bangumiCacheService = mockk<BangumiCacheService>(relaxed = true)
    private val service = ImageGeneratorService(bangumiCacheService)

    @Test
    @Disabled("手动运行生成样例图片")
    fun `generate daily summary sample with airInfo`() {
        // 使用真实的 Bangumi 封面 URL（从 API 获取验证过的）
        val animes = listOf(
            DailySummaryAnime(
                name = "葬送的芙莉莲",
                coverUrl = "https://lain.bgm.tv/r/400/pic/cover/l/13/c5/400602_ZI8Y9.jpg",
                airInfo = "第 24 集"
            ),
            DailySummaryAnime(
                name = "不时轻声地以俄语遮羞的邻座艾莉同学",
                coverUrl = "https://lain.bgm.tv/r/400/pic/cover/l/7c/8e/424883_5X5X2.jpg",
                airInfo = "第 12 集"
            ),
            DailySummaryAnime(
                name = "死神 千年血战篇",
                coverUrl = "https://lain.bgm.tv/r/400/pic/cover/l/b7/58/302286_s3o3E.jpg",
                airInfo = "第 8 集"
            ),
            DailySummaryAnime(
                name = "Start over!",
                coverUrl = "https://lain.bgm.tv/r/400/pic/cover/l/7a/b7/441568_01Onk.jpg",
                airInfo = "第 5 集"
            )
        )

        val imageData = service.generateDailySummaryCard(animes)
        File("/tmp/daily_summary_with_airinfo.png").writeBytes(imageData)
        println("样例图片已保存到 /tmp/daily_summary_with_airinfo.png")
    }

    @Test
    @Disabled("手动运行生成样例图片")
    fun `generate daily summary sample without covers`() {
        // 测试无封面的降级效果
        val animes = listOf(
            DailySummaryAnime(name = "葬送的芙莉莲", airInfo = "第 24 集"),
            DailySummaryAnime(name = "迷宫饭", airInfo = "第 18 集"),
            DailySummaryAnime(name = "药屋少女的呢喃", airInfo = "第 12 集"),
            DailySummaryAnime(name = "我推的孩子 第二季", airInfo = "第 8 集"),
            DailySummaryAnime(name = "无职转生 到了异世界就拿出真本事", airInfo = "第 5 集"),
            DailySummaryAnime(name = "关于我转生变成史莱姆这档事", airInfo = "第 3 集")
        )

        val imageData = service.generateDailySummaryCard(animes)
        File("/tmp/daily_summary_no_cover.png").writeBytes(imageData)
        println("样例图片已保存到 /tmp/daily_summary_no_cover.png")
    }
}
