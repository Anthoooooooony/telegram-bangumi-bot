package `fun`.fantasea.bangumi.task

import `fun`.fantasea.bangumi.client.BangumiClient
import `fun`.fantasea.bangumi.client.Episode
import `fun`.fantasea.bangumi.repository.SubscriptionRepository
import `fun`.fantasea.bangumi.service.NotificationService
import `fun`.fantasea.bangumi.service.SubscriptionService
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * 剧集检查定时任务
 * 每15分钟检查一次是否有新剧集更新
 */
@Component
class EpisodeCheckerTask(
    private val subscriptionRepository: SubscriptionRepository,
    private val subscriptionService: SubscriptionService,
    private val bangumiClient: BangumiClient,
    private val notificationService: NotificationService
) {
    private val log = LoggerFactory.getLogger(EpisodeCheckerTask::class.java)

    /**
     * 每15分钟检查新剧集
     */
    @Scheduled(fixedRate = 15 * 60 * 1000, initialDelay = 60 * 1000)
    fun checkNewEpisodes() {
        log.info("开始检查新剧集更新...")

        runBlocking {
            try {
                // 获取所有订阅，按 subject 分组
                val allSubscriptions = subscriptionRepository.findAll()
                val subscriptionsBySubject = allSubscriptions.groupBy { it.subjectId }

                log.info("共有 {} 个不同番剧需要检查", subscriptionsBySubject.size)

                for ((subjectId, subscriptions) in subscriptionsBySubject) {
                    try {
                        checkSubjectEpisodes(subjectId, subscriptions)
                    } catch (e: Exception) {
                        log.warn("检查 subject {} 失败: {}", subjectId, e.message)
                    }
                }

                log.info("剧集检查完成")
            } catch (e: Exception) {
                log.error("剧集检查任务出错: {}", e.message, e)
            }
        }
    }

    private suspend fun checkSubjectEpisodes(
        subjectId: Int,
        subscriptions: List<`fun`.fantasea.bangumi.entity.Subscription>
    ) {
        // 获取剧集列表
        val episodes = bangumiClient.getEpisodes(subjectId)
        val today = LocalDate.now()

        // 找出已播出的剧集 (type=0 是本篇)
        val airedEpisodes = episodes.data
            .filter { it.type == 0 }
            .filter { isEpisodeAired(it, today) }
            .sortedBy { it.sort }

        if (airedEpisodes.isEmpty()) return

        val latestAiredEp = airedEpisodes.maxOfOrNull { it.sort.toInt() } ?: return

        // 检查每个订阅是否需要通知
        for (subscription in subscriptions) {
            val lastNotified = subscription.lastNotifiedEp

            if (latestAiredEp > lastNotified) {
                // 有新剧集，发送通知
                val newEpisodes = airedEpisodes.filter { it.sort.toInt() > lastNotified }

                for (ep in newEpisodes) {
                    val epNumber = ep.sort.toInt()
                    val epName = ep.nameCn?.takeIf { it.isNotBlank() } ?: ep.name

                    notificationService.sendNewEpisodeNotification(
                        telegramId = subscription.user.telegramId,
                        subscription = subscription,
                        episodeNumber = epNumber,
                        episodeName = epName
                    )
                }

                // 更新已通知集数
                subscriptionService.markNotified(subscription.id!!, latestAiredEp)
                subscriptionService.updateLatestEpisode(subscription.id!!, latestAiredEp)
            }
        }
    }

    /**
     * 判断剧集是否已播出
     */
    private fun isEpisodeAired(episode: Episode, today: LocalDate): Boolean {
        val airdate = episode.airdate ?: return false
        return try {
            val episodeDate = LocalDate.parse(airdate)
            !episodeDate.isAfter(today)
        } catch (e: Exception) {
            false
        }
    }
}
