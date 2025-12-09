package `fun`.fantasea.bangumi.service

import `fun`.fantasea.bangumi.client.BangumiClient
import `fun`.fantasea.bangumi.client.BangumiUser
import `fun`.fantasea.bangumi.entity.User
import `fun`.fantasea.bangumi.repository.UserRepository
import io.mockk.*
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * UserService 测试
 * 使用 MockK 模拟依赖
 */
class UserServiceTest {

    @MockK
    lateinit var userRepository: UserRepository

    @MockK
    lateinit var bangumiClient: BangumiClient

    @MockK
    lateinit var tokenService: TokenService

    @InjectMockKs
    lateinit var userService: UserService

    @BeforeEach
    fun setUp() {
        MockKAnnotations.init(this)
    }

    @Test
    fun `should get or create user when user not exists`() {
        // given
        val telegramId = 123456789L
        val username = "testuser"
        every { userRepository.findByTelegramId(telegramId) } returns null
        every { userRepository.save(any()) } answers { firstArg() }

        // when
        val user = userService.getOrCreateUser(telegramId, username)

        // then
        assertEquals(telegramId, user.telegramId)
        assertEquals(username, user.telegramUsername)
        verify { userRepository.save(any()) }
    }

    @Test
    fun `should return existing user when user exists`() {
        // given
        val telegramId = 123456789L
        val existingUser = User(telegramId = telegramId, telegramUsername = "oldname")
        every { userRepository.findByTelegramId(telegramId) } returns existingUser

        // when
        val user = userService.getOrCreateUser(telegramId, "newname")

        // then
        assertEquals(telegramId, user.telegramId)
        assertEquals("oldname", user.telegramUsername) // 不更新已有用户名
        verify(exactly = 0) { userRepository.save(any()) }
    }

    @Test
    fun `should bind token successfully when token is valid`() = runBlocking {
        // given
        val telegramId = 123456789L
        val token = "valid-bangumi-token"
        val bangumiUser = BangumiUser(id = 12345, username = "bgmuser", nickname = "BGM用户")
        val user = User(telegramId = telegramId, telegramUsername = "testuser")

        every { userRepository.findByTelegramId(telegramId) } returns user
        coEvery { bangumiClient.getMe(token) } returns bangumiUser
        every { tokenService.encrypt(token) } returns "encrypted-token"
        every { userRepository.save(any()) } answers { firstArg() }

        // when
        val result = userService.bindToken(telegramId, token)

        // then
        assertTrue(result.success)
        assertEquals("bgmuser", result.bangumiUsername)
        verify { tokenService.encrypt(token) }
        verify { userRepository.save(match {
            it.bangumiToken == "encrypted-token" &&
            it.bangumiUserId == 12345 &&
            it.bangumiUsername == "bgmuser"
        }) }
    }

    @Test
    fun `should return error when token is invalid`() = runBlocking {
        // given
        val telegramId = 123456789L
        val token = "invalid-token"
        val user = User(telegramId = telegramId, telegramUsername = "testuser")

        every { userRepository.findByTelegramId(telegramId) } returns user
        coEvery { bangumiClient.getMe(token) } throws RuntimeException("401 Unauthorized")

        // when
        val result = userService.bindToken(telegramId, token)

        // then
        assertFalse(result.success)
        assertNotNull(result.error)
        verify(exactly = 0) { tokenService.encrypt(any()) }
        verify(exactly = 0) { userRepository.save(any()) }
    }

    @Test
    fun `should unbind token successfully`() {
        // given
        val telegramId = 123456789L
        val user = User(
            telegramId = telegramId,
            telegramUsername = "testuser",
            bangumiToken = "encrypted-token",
            bangumiUserId = 12345,
            bangumiUsername = "bgmuser"
        )

        every { userRepository.findByTelegramId(telegramId) } returns user
        every { userRepository.save(any()) } answers { firstArg() }

        // when
        val result = userService.unbindToken(telegramId)

        // then
        assertTrue(result)
        verify { userRepository.save(match {
            it.bangumiToken == null &&
            it.bangumiUserId == null &&
            it.bangumiUsername == null
        }) }
    }

    @Test
    fun `should get user status correctly`() {
        // given
        val telegramId = 123456789L
        val user = User(
            telegramId = telegramId,
            telegramUsername = "testuser",
            bangumiToken = "encrypted-token",
            bangumiUserId = 12345,
            bangumiUsername = "bgmuser"
        )

        every { userRepository.findByTelegramId(telegramId) } returns user

        // when
        val status = userService.getUserStatus(telegramId)

        // then
        assertNotNull(status)
        assertTrue(status!!.isBound)
        assertEquals("bgmuser", status.bangumiUsername)
    }

    @Test
    fun `should return unbound status when token not set`() {
        // given
        val telegramId = 123456789L
        val user = User(telegramId = telegramId, telegramUsername = "testuser")

        every { userRepository.findByTelegramId(telegramId) } returns user

        // when
        val status = userService.getUserStatus(telegramId)

        // then
        assertNotNull(status)
        assertFalse(status!!.isBound)
        assertNull(status.bangumiUsername)
    }
}
