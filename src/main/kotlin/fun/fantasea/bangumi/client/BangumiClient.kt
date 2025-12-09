package `fun`.fantasea.bangumi.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.jackson.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class BangumiClient(
    @Value("\${bangumi.api.base-url}") private val baseUrl: String,
    @Value("\${bangumi.api.user-agent}") private val userAgent: String
) {
    private val log = LoggerFactory.getLogger(BangumiClient::class.java)

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            jackson()
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30000
            connectTimeoutMillis = 10000
        }
        defaultRequest {
            header(HttpHeaders.UserAgent, userAgent)
        }
    }

    /**
     * 获取每日放送表 (无需认证)
     */
    suspend fun getCalendar(): List<CalendarItem> {
        log.debug("获取每日放送表")
        return client.get("$baseUrl/calendar").body()
    }

    /**
     * 验证 token 并获取用户信息
     */
    suspend fun getMe(token: String): BangumiUser {
        log.debug("获取当前用户信息")
        return client.get("$baseUrl/v0/me") {
            bearerAuth(token)
        }.body()
    }

    /**
     * 获取用户收藏列表
     */
    suspend fun getUserCollections(
        username: String,
        token: String? = null,
        subjectType: Int = 2, // 2 = 动画
        collectionType: Int = 3 // 3 = 在看
    ): CollectionResponse {
        log.debug("获取用户 {} 的收藏列表", username)
        return client.get("$baseUrl/v0/users/$username/collections") {
            token?.let { bearerAuth(it) }
            parameter("subject_type", subjectType)
            parameter("type", collectionType)
        }.body()
    }

    /**
     * 获取剧集列表
     */
    suspend fun getEpisodes(subjectId: Int): EpisodeResponse {
        log.debug("获取 subject {} 的剧集列表", subjectId)
        return client.get("$baseUrl/v0/episodes") {
            parameter("subject_id", subjectId)
        }.body()
    }
}

// DTO classes

@JsonIgnoreProperties(ignoreUnknown = true)
data class CalendarItem(
    val weekday: Weekday,
    val items: List<CalendarSubject>
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Weekday(
    val en: String,
    val cn: String,
    val ja: String,
    val id: Int
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CalendarSubject(
    val id: Int,
    val name: String,
    @JsonProperty("name_cn") val nameCn: String?,
    val air_date: String?,
    val air_weekday: Int?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BangumiUser(
    val id: Int,
    val username: String,
    val nickname: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CollectionResponse(
    val total: Int,
    val data: List<CollectionItem>
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CollectionItem(
    @JsonProperty("subject_id") val subjectId: Int,
    val subject: CollectionSubject?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CollectionSubject(
    val id: Int,
    val name: String,
    @JsonProperty("name_cn") val nameCn: String?,
    val eps: Int?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EpisodeResponse(
    val total: Int,
    val data: List<Episode>
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Episode(
    val id: Int,
    val type: Int,
    val name: String,
    @JsonProperty("name_cn") val nameCn: String?,
    val sort: Double,
    val ep: Double?,
    val airdate: String?,
    val desc: String?
)
