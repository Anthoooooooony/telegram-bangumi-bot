package `fun`.fantasea.bangumi.service

import `fun`.fantasea.bangumi.client.BangumiClient
import `fun`.fantasea.bangumi.entity.User
import `fun`.fantasea.bangumi.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDateTime

/**
 * 用户服务
 * 处理用户创建、Token 绑定/解绑等操作
 */
@Service
class UserService(
    private val userRepository: UserRepository,
    private val bangumiClient: BangumiClient,
    private val tokenService: TokenService
) {
    private val log = LoggerFactory.getLogger(UserService::class.java)

    /**
     * 获取或创建用户
     */
    fun getOrCreateUser(telegramId: Long, username: String?): User {
        return userRepository.findByTelegramId(telegramId) ?: run {
            log.info("创建新用户: telegramId={}, username={}", telegramId, username)
            val user = User(telegramId = telegramId, telegramUsername = username)
            userRepository.save(user)
        }
    }

    /**
     * 绑定 Bangumi Token
     * @return BindResult 包含成功/失败信息
     */
    suspend fun bindToken(telegramId: Long, token: String): BindResult {
        val user = userRepository.findByTelegramId(telegramId) ?: return BindResult(
            success = false,
            error = "用户不存在，请先使用 /start"
        )

        return try {
            // 验证 token 有效性
            log.info("验证用户 {} 的 Bangumi Token", telegramId)
            val bangumiUser = bangumiClient.getMe(token)

            // 加密存储 token
            val encryptedToken = tokenService.encrypt(token)

            // 更新用户信息
            user.bangumiToken = encryptedToken
            user.bangumiUserId = bangumiUser.id
            user.bangumiUsername = bangumiUser.username
            user.updatedAt = LocalDateTime.now()
            userRepository.save(user)

            log.info("用户 {} 成功绑定 Bangumi 账号: {}", telegramId, bangumiUser.username)
            BindResult(
                success = true,
                bangumiUsername = bangumiUser.username,
                bangumiNickname = bangumiUser.nickname
            )
        } catch (e: Exception) {
            log.warn("Token 验证失败: {}", e.message)
            BindResult(
                success = false,
                error = "Token 验证失败: ${e.message}"
            )
        }
    }

    /**
     * 解绑 Bangumi Token
     */
    fun unbindToken(telegramId: Long): Boolean {
        val user = userRepository.findByTelegramId(telegramId) ?: return false

        user.bangumiToken = null
        user.bangumiUserId = null
        user.bangumiUsername = null
        user.updatedAt = LocalDateTime.now()
        userRepository.save(user)

        log.info("用户 {} 已解绑 Bangumi 账号", telegramId)
        return true
    }

    /**
     * 获取用户状态
     */
    fun getUserStatus(telegramId: Long): UserStatus? {
        val user = userRepository.findByTelegramId(telegramId) ?: return null

        return UserStatus(
            telegramId = user.telegramId,
            telegramUsername = user.telegramUsername,
            isBound = user.bangumiToken != null,
            bangumiUserId = user.bangumiUserId,
            bangumiUsername = user.bangumiUsername,
            dailySummaryEnabled = user.dailySummaryEnabled,
            dailySummaryTime = user.dailySummaryTime.toString()
        )
    }

    /**
     * 获取用户的解密 Token (内部使用)
     */
    fun getDecryptedToken(telegramId: Long): String? {
        val user = userRepository.findByTelegramId(telegramId) ?: return null
        val encryptedToken = user.bangumiToken ?: return null
        return tokenService.decrypt(encryptedToken)
    }
}

/**
 * Token 绑定结果
 */
data class BindResult(
    val success: Boolean,
    val bangumiUsername: String? = null,
    val bangumiNickname: String? = null,
    val error: String? = null
)

/**
 * 用户状态
 */
data class UserStatus(
    val telegramId: Long,
    val telegramUsername: String?,
    val isBound: Boolean,
    val bangumiUserId: Int?,
    val bangumiUsername: String?,
    val dailySummaryEnabled: Boolean,
    val dailySummaryTime: String
)
