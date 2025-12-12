package `fun`.fantasea.bangumi.service

import `fun`.fantasea.bangumi.client.*
import `fun`.fantasea.bangumi.entity.Subscription
import `fun`.fantasea.bangumi.entity.User
import `fun`.fantasea.bangumi.repository.SubscriptionRepository
import `fun`.fantasea.bangumi.repository.UserRepository
import io.mockk.*
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * SubscriptionService 测试
 */
class SubscriptionServiceTest {

    @MockK
    lateinit var subscriptionRepository: SubscriptionRepository

    @MockK
    lateinit var userRepository: UserRepository

    @MockK
    lateinit var bangumiClient: BangumiClient

    @MockK
    lateinit var userService: UserService

    @InjectMockKs
    lateinit var subscriptionService: SubscriptionService

    private val testUser = User(
        id = 1L,
        telegramId = 123456789L,
        telegramUsername = "testuser",
        bangumiToken = "encrypted-token",
        bangumiUserId = 12345,
        bangumiUsername = "bgmuser"
    )

    @BeforeEach
    fun setUp() {
        MockKAnnotations.init(this)
    }

    @Test
    fun `should sync subscriptions from bangumi`() = runBlocking {
        // given
        val token = "decrypted-token"
        val collectionResponse = CollectionResponse(
            total = 2,
            data = listOf(
                CollectionItem(
                    subjectId = 100,
                    subject = CollectionSubject(id = 100, name = "Anime A", nameCn = "动画A", eps = 12)
                ),
                CollectionItem(
                    subjectId = 200,
                    subject = CollectionSubject(id = 200, name = "Anime B", nameCn = "动画B", eps = 24)
                )
            )
        )

        every { userRepository.findByTelegramId(123456789L) } returns testUser
        every { userService.getDecryptedToken(123456789L) } returns token
        coEvery { bangumiClient.getUserCollections("bgmuser", token) } returns collectionResponse
        coEvery { bangumiClient.getEpisodes(any(), any()) } returns EpisodeResponse(
            total = 0,
            data = emptyList()
        )
        every { subscriptionRepository.findByUserAndSubjectId(testUser, any()) } returns null
        every { subscriptionRepository.save(any()) } answers { firstArg() }

        // when
        val result = subscriptionService.syncSubscriptions(123456789L)

        // then
        assertTrue(result.success)
        assertEquals(2, result.syncedCount)
        verify(exactly = 2) { subscriptionRepository.save(any()) }
    }

    @Test
    fun `should return error when user not bound`() = runBlocking {
        // given
        val unboundUser = User(telegramId = 123456789L, telegramUsername = "testuser")
        every { userRepository.findByTelegramId(123456789L) } returns unboundUser
        every { userService.getDecryptedToken(123456789L) } returns null

        // when
        val result = subscriptionService.syncSubscriptions(123456789L)

        // then
        assertFalse(result.success)
        assertNotNull(result.error)
        verify(exactly = 0) { subscriptionRepository.save(any()) }
    }

    @Test
    fun `should get user subscriptions`() {
        // given
        val subscriptions = listOf(
            Subscription(
                user = testUser,
                subjectId = 100,
                subjectName = "Anime A",
                subjectNameCn = "动画A"
            ),
            Subscription(
                user = testUser,
                subjectId = 200,
                subjectName = "Anime B",
                subjectNameCn = "动画B"
            )
        )

        every { subscriptionRepository.findByUserTelegramId(123456789L) } returns subscriptions

        // when
        val result = subscriptionService.getUserSubscriptions(123456789L)

        // then
        assertEquals(2, result.size)
        assertEquals("动画A", result[0].subjectNameCn)
    }

    @Test
    fun `should update subscription when already exists`() = runBlocking {
        // given
        val token = "decrypted-token"
        val existingSubscription = Subscription(
            id = 1L,
            user = testUser,
            subjectId = 100,
            subjectName = "Old Name",
            subjectNameCn = "旧名称"
        )
        val collectionResponse = CollectionResponse(
            total = 1,
            data = listOf(
                CollectionItem(
                    subjectId = 100,
                    subject = CollectionSubject(id = 100, name = "New Name", nameCn = "新名称", eps = 12)
                )
            )
        )

        every { userRepository.findByTelegramId(123456789L) } returns testUser
        every { userService.getDecryptedToken(123456789L) } returns token
        coEvery { bangumiClient.getUserCollections("bgmuser", token) } returns collectionResponse
        every { subscriptionRepository.findByUserAndSubjectId(testUser, 100) } returns existingSubscription
        every { subscriptionRepository.save(any()) } answers { firstArg() }

        // when
        val result = subscriptionService.syncSubscriptions(123456789L)

        // then
        assertTrue(result.success)
        verify { subscriptionRepository.save(match {
            it.subjectName == "New Name" && it.subjectNameCn == "新名称"
        }) }
    }
}
