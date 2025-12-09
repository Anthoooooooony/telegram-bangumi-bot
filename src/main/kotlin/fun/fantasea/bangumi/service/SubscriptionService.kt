package `fun`.fantasea.bangumi.service

import `fun`.fantasea.bangumi.client.BangumiClient
import `fun`.fantasea.bangumi.entity.Subscription
import `fun`.fantasea.bangumi.repository.SubscriptionRepository
import `fun`.fantasea.bangumi.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDateTime

/**
 * 订阅服务
 * 处理追番列表同步和管理
 */
@Service
class SubscriptionService(
    private val subscriptionRepository: SubscriptionRepository,
    private val userRepository: UserRepository,
    private val bangumiClient: BangumiClient,
    private val userService: UserService
) {
    private val log = LoggerFactory.getLogger(SubscriptionService::class.java)

    /**
     * 从 Bangumi 同步用户追番列表
     */
    suspend fun syncSubscriptions(telegramId: Long): SyncResult {
        val user = userRepository.findByTelegramId(telegramId) ?: return SyncResult(
            success = false,
            error = "用户不存在"
        )

        val token = userService.getDecryptedToken(telegramId) ?: return SyncResult(
            success = false,
            error = "未绑定 Bangumi 账号，请先使用 /bindtoken 绑定"
        )

        val bangumiUsername = user.bangumiUsername ?: return SyncResult(
            success = false,
            error = "Bangumi 用户名不存在"
        )

        return try {
            log.info("开始同步用户 {} 的追番列表", telegramId)

            // 获取用户在看的动画
            val collections = bangumiClient.getUserCollections(
                username = bangumiUsername,
                token = token,
                subjectType = 2, // 动画
                collectionType = 3 // 在看
            )

            var syncedCount = 0

            for (item in collections.data) {
                val subject = item.subject ?: continue

                // 查找或创建订阅
                val existingSubscription = subscriptionRepository.findByUserAndSubjectId(user, item.subjectId)
                val isNewSubscription = existingSubscription == null
                val subscription = existingSubscription
                    ?: Subscription(user = user, subjectId = item.subjectId, subjectName = subject.name)

                // 更新信息
                subscription.subjectName = subject.name
                subscription.subjectNameCn = subject.nameCn
                subscription.totalEpisodes = subject.eps
                subscription.updatedAt = LocalDateTime.now()

                // 新订阅时，初始化 lastNotifiedEp 为当前已播出的最新集数，避免推送历史剧集
                if (isNewSubscription) {
                    try {
                        val latestAiredEp = getLatestAiredEpisode(item.subjectId)
                        subscription.lastNotifiedEp = latestAiredEp
                        subscription.latestAiredEp = latestAiredEp
                        log.debug("新订阅 {} 初始化 lastNotifiedEp 为 {}", subject.name, latestAiredEp)
                    } catch (e: Exception) {
                        log.warn("获取 {} 剧集信息失败: {}", subject.name, e.message)
                    }
                }

                subscriptionRepository.save(subscription)
                syncedCount++
            }

            log.info("用户 {} 同步完成，共 {} 部在看", telegramId, syncedCount)
            SyncResult(success = true, syncedCount = syncedCount)
        } catch (e: Exception) {
            log.error("同步失败: {}", e.message, e)
            SyncResult(success = false, error = "同步失败: ${e.message}")
        }
    }

    /**
     * 获取用户的追番列表
     */
    fun getUserSubscriptions(telegramId: Long): List<Subscription> {
        return subscriptionRepository.findByUserTelegramId(telegramId)
    }

    /**
     * 获取所有有订阅的用户及其订阅
     */
    fun getAllActiveSubscriptions(): Map<Long, List<Subscription>> {
        return subscriptionRepository.findAll()
            .groupBy { it.user.telegramId }
    }

    /**
     * 更新订阅的最新播出集数
     */
    fun updateLatestEpisode(subscriptionId: Long, episodeNumber: Int) {
        subscriptionRepository.findById(subscriptionId).ifPresent { subscription ->
            subscription.latestAiredEp = episodeNumber
            subscription.updatedAt = LocalDateTime.now()
            subscriptionRepository.save(subscription)
        }
    }

    /**
     * 标记通知已发送
     */
    fun markNotified(subscriptionId: Long, episodeNumber: Int) {
        subscriptionRepository.findById(subscriptionId).ifPresent { subscription ->
            subscription.lastNotifiedEp = episodeNumber
            subscription.updatedAt = LocalDateTime.now()
            subscriptionRepository.save(subscription)
        }
    }

    /**
     * 获取番剧当前已播出的最新集数
     */
    private suspend fun getLatestAiredEpisode(subjectId: Int): Int {
        val episodes = bangumiClient.getEpisodes(subjectId)
        val today = java.time.LocalDate.now()

        return episodes.data
            .filter { it.type == 0 } // 本篇
            .filter { ep ->
                val airdate = ep.airdate ?: return@filter false
                try {
                    val episodeDate = java.time.LocalDate.parse(airdate)
                    !episodeDate.isAfter(today)
                } catch (e: Exception) {
                    false
                }
            }
            .maxOfOrNull { it.sort.toInt() } ?: 0
    }
}

/**
 * 同步结果
 */
data class SyncResult(
    val success: Boolean,
    val syncedCount: Int = 0,
    val error: String? = null
)
