package `fun`.fantasea.bangumi.service

import `fun`.fantasea.bangumi.client.BangumiClient
import `fun`.fantasea.bangumi.client.BangumiDataClient
import `fun`.fantasea.bangumi.client.Episode
import `fun`.fantasea.bangumi.entity.Anime
import `fun`.fantasea.bangumi.repository.AnimeRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 番剧服务
 * 提供番剧数据的查询和计算功能
 * 整合 Bangumi API 和 BangumiData 两个数据源
 */
@Service
class AnimeService(
    private val animeRepository: AnimeRepository,
    private val bangumiClient: BangumiClient,
    private val bangumiDataClient: BangumiDataClient,
    @param:Value("\${anime.timezone:Asia/Shanghai}") private val timezone: String
) {
    private val log = LoggerFactory.getLogger(AnimeService::class.java)

    private val zoneId: ZoneId by lazy { ZoneId.of(timezone) }

    companion object {
        private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")
        private val DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    }

    /**
     * 创建 Anime 记录
     * 整合 Bangumi API（基本信息）和 BangumiData（时间和平台信息）
     * 调用方应先检查是否已存在
     */
    suspend fun createAnime(subjectId: Int): Anime {
        // 从 Bangumi API 获取基本信息
        val subject = bangumiClient.getSubject(subjectId)

        // 从 BangumiDataClient 缓存获取时间和平台信息
        val bangumiData = bangumiDataClient.getBangumiData(subjectId)

        // 合并数据创建 Anime
        val anime = Anime(
            subjectId = subjectId,
            name = subject.name,
            nameCn = subject.nameCn,
            coverUrl = subject.images?.common,
            beginTime = bangumiData?.beginTime,
            endTime = bangumiData?.endTime,
            broadcastPeriod = bangumiData?.broadcastPeriod,
            hasBangumiData = bangumiData != null
        )

        // 添加播放平台
        bangumiData?.platforms?.let { anime.platforms.addAll(it) }

        log.info("创建番剧记录: {} ({}), hasBangumiData={}", subject.name, subjectId, bangumiData != null)
        return animeRepository.save(anime)
    }

    /**
     * 计算指定剧集的精确播出时间
     * @return 播出时间，如果无法计算则返回 null
     */
    fun calculateEpisodeAirTime(anime: Anime, episodeSort: Int): Instant? {
        val beginTime = anime.beginTime ?: return null
        val period = anime.broadcastPeriod ?: return null

        return try {
            val duration = Duration.parse(period)
            beginTime.plus(duration.multipliedBy((episodeSort - 1).toLong()))
        } catch (e: Exception) {
            log.debug("无法解析播出周期: {}", period) // todo 将所有 debug 日志输出到一个文件
            null
        }
    }

    /**
     * 判断剧集是否已播出
     * 优先使用 BangumiData 精确时间，回退到 Episode.airdate
     */
    fun isEpisodeAired(
        anime: Anime,
        episode: Episode,
        now: Instant = Instant.now()
    ): Boolean {
        // 优先使用 BangumiData 精确时间
        if (anime.hasBangumiData) {
            val airTime = calculateEpisodeAirTime(anime, episode.sort.toInt())
            if (airTime != null) {
                return !airTime.isAfter(now)
            }
        }

        // 回退到 Episode.airdate (仅日期)
        return isEpisodeAiredByDate(episode)
    }

    /**
     * 使用日期判断剧集是否已播出（回退方案）
     */
    fun isEpisodeAiredByDate(episode: Episode): Boolean {
        val airdate = episode.airdate ?: return false
        return try {
            val episodeDate = LocalDate.parse(airdate)
            val today = LocalDate.now(zoneId)
            !episodeDate.isAfter(today)
        } catch (e: Exception) {
            log.debug("解析播出日期失败: airdate={}, error={}", airdate, e.message)
            false
        }
    }

    /**
     * 格式化播出时间显示
     * @return "今天 16:35" / "明天 22:00" / "昨天 16:35" / "01-15 16:35"，无法计算时返回 null
     */
    fun formatAirTime(anime: Anime, episodeSort: Int): String? {
        val airTime = calculateEpisodeAirTime(anime, episodeSort) ?: return null
        val airDateTime = LocalDateTime.ofInstant(airTime, zoneId)
        val today = LocalDate.now(zoneId)
        val airDate = airDateTime.toLocalDate()
        val timeStr = airDateTime.format(TIME_FORMATTER)

        return when (airDate) {
            today -> "今天 $timeStr"
            today.plusDays(1) -> "明天 $timeStr"
            today.minusDays(1) -> "昨天 $timeStr"
            else -> airDateTime.format(DATE_TIME_FORMATTER)
        }
    }

    /**
     * 获取番剧的显示名称（优先中文名）
     */
    fun getDisplayName(anime: Anime): String {
        return anime.nameCn?.takeIf { it.isNotBlank() } ?: anime.name
    }
}
