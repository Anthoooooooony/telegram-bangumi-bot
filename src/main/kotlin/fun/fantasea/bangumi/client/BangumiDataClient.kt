package `fun`.fantasea.bangumi.client

import `fun`.fantasea.bangumi.entity.AnimePlatform
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.runBlocking
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.jackson.*
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Bangumi Data 客户端
 * 从 bangumi-data 获取番剧播放平台信息和时间数据，建立内存缓存供查询
 * https://github.com/bangumi-data/bangumi-data
 */
@Component
class BangumiDataClient(
    @param:Value("\${telegram.proxy.host:}") private val proxyHost: String,
    @param:Value("\${telegram.proxy.port:0}") private val proxyPort: Int
) {
    private val log = LoggerFactory.getLogger(BangumiDataClient::class.java)

    private val dataUrl = "https://cdn.jsdelivr.net/npm/bangumi-data@0.3/dist/data.json"

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            jackson()
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 60000
            connectTimeoutMillis = 30000
        }
        if (proxyHost.isNotBlank() && proxyPort > 0) {
            engine {
                proxy = ProxyBuilder.http(Url("http://$proxyHost:$proxyPort"))
            }
        }
    }

    private val objectMapper = jacksonObjectMapper()

    // 内存缓存：subjectId -> 番剧信息
    private val bangumiDataCache = ConcurrentHashMap<Int, BangumiDataInfo>()

    /**
     * 启动时加载数据
     */
    @PostConstruct
    private fun init() {
        refreshData()
    }

    companion object {
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val INITIAL_RETRY_DELAY_MS = 1000L
    }

    /**
     * 每天凌晨 4 点刷新数据
     */
    @Scheduled(cron = "0 0 4 * * ?")
    fun refreshData() {
        log.info("开始加载 bangumi-data 数据...")

        val jsonText = fetchDataWithRetry() ?: return

        try {
            val data: BangumiData = objectMapper.readValue(jsonText)
            val siteMeta = data.siteMeta

            // 构建内存缓存
            bangumiDataCache.clear()

            for (item in data.items) {
                val bangumiSite = item.sites.find { it.site == "bangumi" && it.id != null }
                if (bangumiSite == null) continue

                val bangumiId = bangumiSite.id!!
                val subjectId = bangumiId.toIntOrNull() ?: continue

                // 解析播放平台信息
                val platforms = item.sites
                    .filter { it.site != "bangumi" && it.id != null }
                    .filter { siteMeta[it.site]?.type == "onair" } // 只保留播放平台
                    .mapNotNull { site ->
                        val meta = siteMeta[site.site] ?: return@mapNotNull null
                        val siteId = site.id ?: return@mapNotNull null
                        val url = meta.urlTemplate.replace("{{id}}", siteId)
                        AnimePlatform(
                            name = meta.title,
                            url = url,
                            regions = (site.regions ?: meta.regions)?.joinToString(",")
                        )
                    }

                // 解析时间信息
                val beginTime = parseIsoInstant(item.begin)
                val endTime = parseIsoInstant(item.end)
                val broadcastPeriod = parseBroadcastPeriod(item.broadcast)

                bangumiDataCache[subjectId] = BangumiDataInfo(
                    beginTime = beginTime,
                    endTime = endTime,
                    broadcastPeriod = broadcastPeriod,
                    platforms = platforms
                )
            }

            val platformCount = bangumiDataCache.values.count { it.platforms.isNotEmpty() }
            val timeCount = bangumiDataCache.values.count { it.beginTime != null }
            log.info(
                "bangumi-data 缓存加载完成，共 {} 个番剧，{} 个有播放平台信息，{} 个有时间信息",
                bangumiDataCache.size, platformCount, timeCount
            )
        } catch (e: Exception) {
            log.error("解析 bangumi-data 失败: {}", e.message, e)
        }
    }

    /**
     * 带重试机制的数据获取
     * 使用指数退避策略，最多重试 MAX_RETRY_ATTEMPTS 次
     */
    private fun fetchDataWithRetry(): String? {
        var lastException: Exception? = null

        repeat(MAX_RETRY_ATTEMPTS) { attempt ->
            try {
                return runBlocking {
                    client.get(dataUrl).body<String>()
                }
            } catch (e: Exception) {
                lastException = e
                val delayMs = INITIAL_RETRY_DELAY_MS * (1 shl attempt) // 指数退避: 1s, 2s, 4s

                if (attempt < MAX_RETRY_ATTEMPTS - 1) {
                    log.warn("获取 bangumi-data 失败 (第{}次尝试)，{}ms 后重试: {}",
                        attempt + 1, delayMs, e.message)
                    Thread.sleep(delayMs)
                }
            }
        }

        log.error("获取 bangumi-data 失败，已重试 {} 次: {}", MAX_RETRY_ATTEMPTS, lastException?.message, lastException)
        return null
    }

    /**
     * 解析 ISO 8601 时间字符串
     */
    private fun parseIsoInstant(isoString: String?): Instant? {
        if (isoString.isNullOrBlank()) return null
        return try {
            Instant.parse(isoString)
        } catch (e: Exception) {
            log.debug("解析时间失败: {}", isoString)
            null
        }
    }

    /**
     * 解析播出周期
     * 格式: "R/2020-01-01T13:00:00Z/P7D" -> "P7D"
     */
    private fun parseBroadcastPeriod(broadcast: String?): String? {
        if (broadcast.isNullOrBlank()) return null
        val parts = broadcast.split("/")
        return if (parts.size >= 3) parts[2] else null
    }

    /**
     * 获取指定番剧的 BangumiData 信息（从缓存查询）
     */
    fun getBangumiData(subjectId: Int): BangumiDataInfo? {
        return bangumiDataCache[subjectId]
    }

    /**
     * 获取指定番剧的播放平台（从缓存查询）
     */
    fun getPlatforms(subjectId: Int): List<PlatformInfo> {
        val info = bangumiDataCache[subjectId] ?: return emptyList()
        return info.platforms.map { platform ->
            PlatformInfo(
                name = platform.name,
                url = platform.url,
                regions = platform.regions?.split(",")?.filter { it.isNotBlank() }
            )
        }
    }
}

/**
 * 播放平台信息（DTO）
 */
data class PlatformInfo(
    val name: String,
    val url: String,
    val regions: List<String>?
)

/**
 * BangumiData 信息
 * 包含从 bangumi-data 解析的时间信息和播放平台
 */
data class BangumiDataInfo(
    val beginTime: Instant?,
    val endTime: Instant?,
    val broadcastPeriod: String?,  // "P7D" = 每周, "P1D" = 每天, "P0D" = 一次性
    val platforms: List<AnimePlatform>
)

// DTO classes for bangumi-data

@JsonIgnoreProperties(ignoreUnknown = true)
data class BangumiData(
    val siteMeta: Map<String, SiteMeta>,
    val items: List<BangumiDataItem>
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SiteMeta(
    val title: String,
    val urlTemplate: String,
    val type: String? = null,
    val regions: List<String>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BangumiDataItem(
    val title: String,
    @field:JsonProperty("titleTranslate") val titleTranslate: Map<String, List<String>>? = null,
    val type: String? = null,
    val begin: String? = null,      // 首播时间 (ISO 8601)
    val end: String? = null,        // 完结时间 (ISO 8601)
    val broadcast: String? = null,  // 播出周期 (如 "R/2020-01-01T13:00:00Z/P7D")
    val sites: List<BangumiDataSite> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BangumiDataSite(
    val site: String,
    val id: String? = null,
    val regions: List<String>? = null
)
