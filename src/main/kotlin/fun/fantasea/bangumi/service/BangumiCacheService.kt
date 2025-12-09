package `fun`.fantasea.bangumi.service

import `fun`.fantasea.bangumi.client.EpisodeResponse
import `fun`.fantasea.bangumi.client.SubjectDetail
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentHashMap

/**
 * 番剧资源缓存服务
 * 缓存番剧详情、剧集列表和封面图片，减少 API 调用和网络请求
 */
@Service
class BangumiCacheService {
    private val log = LoggerFactory.getLogger(BangumiCacheService::class.java)

    companion object {
        // 缓存过期时间（毫秒）
        private const val SUBJECT_TTL_MS = 60 * 60 * 1000L       // 番剧详情缓存 1 小时
        private const val EPISODES_TTL_MS = 60 * 60 * 1000L      // 剧集列表缓存 1 小时
        private const val COVER_IMAGE_TTL_MS = 24 * 60 * 60 * 1000L  // 封面图片缓存 24 小时
    }

    // 缓存条目，包含数据和过期时间
    private data class CacheEntry<T>(
        val data: T,
        val expireAt: Long
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() > expireAt
    }

    // 番剧详情缓存 (subjectId -> SubjectDetail)
    private val subjectCache = ConcurrentHashMap<Int, CacheEntry<SubjectDetail>>()

    // 剧集列表缓存 (subjectId -> EpisodeResponse)
    private val episodesCache = ConcurrentHashMap<Int, CacheEntry<EpisodeResponse>>()

    // 封面图片缓存 (coverUrl -> BufferedImage)
    private val coverImageCache = ConcurrentHashMap<String, CacheEntry<BufferedImage>>()

    /**
     * 获取缓存的番剧详情
     */
    fun getSubject(subjectId: Int): SubjectDetail? {
        val entry = subjectCache[subjectId] ?: return null
        if (entry.isExpired()) {
            subjectCache.remove(subjectId)
            return null
        }
        return entry.data
    }

    /**
     * 缓存番剧详情
     */
    fun putSubject(subjectId: Int, subject: SubjectDetail) {
        subjectCache[subjectId] = CacheEntry(subject, System.currentTimeMillis() + SUBJECT_TTL_MS)
    }

    /**
     * 获取缓存的剧集列表
     */
    fun getEpisodes(subjectId: Int): EpisodeResponse? {
        val entry = episodesCache[subjectId] ?: return null
        if (entry.isExpired()) {
            episodesCache.remove(subjectId)
            return null
        }
        return entry.data
    }

    /**
     * 缓存剧集列表
     */
    fun putEpisodes(subjectId: Int, episodes: EpisodeResponse) {
        episodesCache[subjectId] = CacheEntry(episodes, System.currentTimeMillis() + EPISODES_TTL_MS)
    }

    /**
     * 获取缓存的封面图片
     */
    fun getCoverImage(coverUrl: String): BufferedImage? {
        val entry = coverImageCache[coverUrl] ?: return null
        if (entry.isExpired()) {
            coverImageCache.remove(coverUrl)
            return null
        }
        return entry.data
    }

    /**
     * 缓存封面图片
     */
    fun putCoverImage(coverUrl: String, image: BufferedImage) {
        coverImageCache[coverUrl] = CacheEntry(image, System.currentTimeMillis() + COVER_IMAGE_TTL_MS)
    }

    /**
     * 定时清理过期缓存条目（每 10 分钟执行一次）
     */
    @Scheduled(fixedRate = 10 * 60 * 1000)
    fun cleanupExpiredEntries() {
        val now = System.currentTimeMillis()
        var cleanedCount = 0

        subjectCache.entries.removeIf { entry ->
            val expired = entry.value.expireAt < now
            if (expired) cleanedCount++
            expired
        }

        episodesCache.entries.removeIf { entry ->
            val expired = entry.value.expireAt < now
            if (expired) cleanedCount++
            expired
        }

        coverImageCache.entries.removeIf { entry ->
            val expired = entry.value.expireAt < now
            if (expired) cleanedCount++
            expired
        }

        if (cleanedCount > 0) {
            log.debug("清理过期缓存: {} 条", cleanedCount)
        }
    }

    /**
     * 获取缓存统计信息
     */
    fun getStats(): CacheStats {
        return CacheStats(
            subjectCount = subjectCache.size,
            episodesCount = episodesCache.size,
            coverImageCount = coverImageCache.size
        )
    }
}

data class CacheStats(
    val subjectCount: Int,
    val episodesCount: Int,
    val coverImageCount: Int
)
