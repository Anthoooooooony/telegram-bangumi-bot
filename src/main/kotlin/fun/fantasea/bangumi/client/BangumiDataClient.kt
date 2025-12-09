package `fun`.fantasea.bangumi.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.jackson.*
import io.ktor.client.engine.*
import io.ktor.http.*
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Bangumi Data 客户端
 * 从 bangumi-data 获取番剧播放平台信息
 * https://github.com/bangumi-data/bangumi-data
 */
@Component
class BangumiDataClient(
    @Value("\${telegram.proxy.host:}") private val proxyHost: String,
    @Value("\${telegram.proxy.port:0}") private val proxyPort: Int
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

    // 复用 ObjectMapper 实例
    private val objectMapper = jacksonObjectMapper()

    // 缓存：Bangumi ID -> 播放平台列表
    private val platformCache = ConcurrentHashMap<String, List<PlatformInfo>>()

    // 站点元数据
    private var siteMeta: Map<String, SiteMeta> = emptyMap()

    /**
     * 启动时加载数据
     */
    @PostConstruct
    fun init() {
        refreshData()
    }

    /**
     * 每天凌晨 4 点刷新数据
     */
    @Scheduled(cron = "0 0 4 * * ?")
    fun refreshData() {
        log.info("开始加载 bangumi-data 数据...")
        try {
            val jsonText = kotlinx.coroutines.runBlocking {
                client.get(dataUrl).body<String>()
            }

            val data: BangumiData = objectMapper.readValue(jsonText)

            siteMeta = data.siteMeta

            // 构建缓存：按 Bangumi ID 索引
            platformCache.clear()
            for (item in data.items) {
                val bangumiSite = item.sites.find { it.site == "bangumi" && it.id != null }
                if (bangumiSite != null) {
                    val platforms = item.sites
                        .filter { it.site != "bangumi" && it.id != null }
                        .filter { siteMeta[it.site]?.type == "onair" } // 只保留播放平台
                        .mapNotNull { site ->
                            val meta = siteMeta[site.site] ?: return@mapNotNull null
                            val siteId = site.id ?: return@mapNotNull null
                            val url = meta.urlTemplate.replace("{{id}}", siteId)
                            PlatformInfo(
                                name = meta.title,
                                url = url,
                                regions = site.regions ?: meta.regions
                            )
                        }
                    if (platforms.isNotEmpty()) {
                        platformCache[bangumiSite.id!!] = platforms
                    }
                }
            }

            log.info("bangumi-data 加载完成，共 {} 个番剧有播放平台信息", platformCache.size)
        } catch (e: Exception) {
            log.error("加载 bangumi-data 失败: {}", e.message, e)
        }
    }

    /**
     * 根据 Bangumi Subject ID 获取播放平台
     */
    fun getPlatforms(subjectId: Int): List<PlatformInfo> {
        return platformCache[subjectId.toString()] ?: emptyList()
    }

    /**
     * 生成播放平台的 Markdown 链接
     * 格式：[平台A](url) | [平台B](url) | [平台C](url)
     */
    fun generatePlatformLinks(subjectId: Int, region: String? = "CN"): String? {
        val platforms = getPlatforms(subjectId)
        if (platforms.isEmpty()) return null

        // 过滤区域（如果指定）并限制数量
        val filtered = if (region != null) {
            platforms.filter { it.regions == null || it.regions.contains(region) }
        } else {
            platforms
        }.take(5) // 最多显示 5 个平台

        if (filtered.isEmpty()) return null

        return filtered.joinToString(" | ") { platform ->
            "[${platform.name}](${platform.url})"
        }
    }
}

/**
 * 播放平台信息
 */
data class PlatformInfo(
    val name: String,
    val url: String,
    val regions: List<String>?
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
    @JsonProperty("titleTranslate") val titleTranslate: Map<String, List<String>>? = null,
    val type: String? = null,
    val sites: List<BangumiDataSite> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BangumiDataSite(
    val site: String,
    val id: String? = null,
    val regions: List<String>? = null
)
