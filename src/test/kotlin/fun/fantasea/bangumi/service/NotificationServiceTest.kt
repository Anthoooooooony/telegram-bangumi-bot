package `fun`.fantasea.bangumi.service

import `fun`.fantasea.bangumi.bot.BangumiBot
import `fun`.fantasea.bangumi.client.BangumiClient
import `fun`.fantasea.bangumi.client.BangumiDataClient
import `fun`.fantasea.bangumi.client.PlatformInfo
import `fun`.fantasea.bangumi.client.SubjectDetail
import `fun`.fantasea.bangumi.client.SubjectImages
import `fun`.fantasea.bangumi.entity.Subscription
import `fun`.fantasea.bangumi.entity.User
import io.mockk.*
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * NotificationService 测试
 */
class NotificationServiceTest {

    @MockK
    lateinit var bangumiBot: BangumiBot

    @MockK
    lateinit var bangumiDataClient: BangumiDataClient

    @MockK
    lateinit var bangumiClient: BangumiClient

    @MockK
    lateinit var imageGeneratorService: ImageGeneratorService

    @InjectMockKs
    lateinit var notificationService: NotificationService

    @BeforeEach
    fun setUp() {
        MockKAnnotations.init(this)
    }

    @Test
    fun `should send image notification for single episode`() = runBlocking {
        // given
        val telegramId = 123456789L
        val subscription = createSubscription()
        val episodes = listOf(EpisodeInfo(epNumber = 5, name = "测试标题"))
        val imageData = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) // PNG header

        coEvery { bangumiClient.getSubject(100) } returns SubjectDetail(
            id = 100,
            name = "Test Anime",
            nameCn = "测试番剧",
            images = SubjectImages(
                large = null,
                common = "https://example.com/cover.jpg",
                medium = null,
                small = null,
                grid = null
            )
        )
        every { bangumiDataClient.getPlatforms(100) } returns listOf(
            PlatformInfo(name = "哔哩哔哩", url = "https://bilibili.com", regions = listOf("CN"))
        )
        every {
            imageGeneratorService.generateNotificationCard(
                animeName = "测试番剧",
                episodeText = "第 5 集",
                episodeName = "测试标题",
                coverUrl = "https://example.com/cover.jpg",
                platforms = any()
            )
        } returns imageData
        every { bangumiBot.sendPhoto(telegramId, imageData, any()) } just Runs

        // when
        notificationService.sendNewEpisodeNotification(telegramId, subscription, episodes)

        // then
        verify { bangumiBot.sendPhoto(telegramId, imageData, any()) }
    }

    @Test
    fun `should send image notification for multiple episodes`() = runBlocking {
        // given
        val telegramId = 123456789L
        val subscription = createSubscription()
        val episodes = listOf(
            EpisodeInfo(epNumber = 5, name = null),
            EpisodeInfo(epNumber = 6, name = null),
            EpisodeInfo(epNumber = 7, name = null)
        )
        val imageData = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

        coEvery { bangumiClient.getSubject(100) } returns SubjectDetail(
            id = 100, name = "Test", nameCn = "测试番剧", images = null
        )
        every { bangumiDataClient.getPlatforms(100) } returns emptyList()
        every {
            imageGeneratorService.generateNotificationCard(
                animeName = "测试番剧",
                episodeText = "第 5\\-7 集",
                episodeName = null,
                coverUrl = null,
                platforms = any()
            )
        } returns imageData
        every { bangumiBot.sendPhoto(telegramId, imageData, any()) } just Runs

        // when
        notificationService.sendNewEpisodeNotification(telegramId, subscription, episodes)

        // then
        verify {
            imageGeneratorService.generateNotificationCard(
                animeName = "测试番剧",
                episodeText = "第 5\\-7 集",
                episodeName = null,
                coverUrl = null,
                platforms = any()
            )
        }
    }

    @Test
    fun `should not send notification for empty episodes list`() = runBlocking {
        // given
        val telegramId = 123456789L
        val subscription = createSubscription()
        val episodes = emptyList<EpisodeInfo>()

        // when
        notificationService.sendNewEpisodeNotification(telegramId, subscription, episodes)

        // then
        verify(exactly = 0) { bangumiBot.sendPhoto(any(), any(), any()) }
        verify(exactly = 0) { bangumiBot.sendMessageMarkdown(any(), any()) }
    }

    @Test
    fun `should send daily summary as image`() {
        // given
        val telegramId = 123456789L
        val todayAnimes = listOf(
            TodayAnimeInfo(1, "Anime A", "番剧A"),
            TodayAnimeInfo(2, "Anime B", null)
        )
        val imageData = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

        every {
            imageGeneratorService.generateDailySummaryCard(match { animes ->
                animes.size == 2 &&
                animes[0].name == "番剧A" &&
                animes[1].name == "Anime B"
            })
        } returns imageData
        every { bangumiBot.sendPhoto(telegramId, imageData, any()) } just Runs

        // when
        notificationService.sendDailySummary(telegramId, todayAnimes)

        // then
        verify { bangumiBot.sendPhoto(telegramId, imageData, any()) }
        verify(exactly = 0) { bangumiBot.sendMessage(any(), any()) }
    }

    @Test
    fun `should send empty daily summary message`() {
        // given
        val telegramId = 123456789L
        val todayAnimes = emptyList<TodayAnimeInfo>()

        every { bangumiBot.sendMessage(telegramId, any()) } just Runs

        // when
        notificationService.sendDailySummary(telegramId, todayAnimes)

        // then
        verify {
            bangumiBot.sendMessage(telegramId, "今日没有追番更新。")
        }
    }

    private fun createSubscription(): Subscription {
        val user = User(telegramId = 123456789L, telegramUsername = "testuser")
        return Subscription(
            id = 1L,
            user = user,
            subjectId = 100,
            subjectName = "Test Anime",
            subjectNameCn = "测试番剧",
            totalEpisodes = 12,
            lastNotifiedEp = 4
        )
    }
}
