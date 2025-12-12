package `fun`.fantasea.bangumi.entity

import jakarta.persistence.*
import java.time.Instant

/**
 * 番剧信息
 * 整合 Bangumi API 和 BangumiData 两个数据源的数据
 */
@Entity
@Table(name = "animes")
class Anime(
    // Bangumi Subject ID 作为主键
    @field:Id
    @field:Column(name = "subject_id")
    var subjectId: Int,

    // 基本信息（来自 Bangumi API）
    @field:Column(name = "name", nullable = false)
    var name: String,

    @field:Column(name = "name_cn")
    var nameCn: String? = null,

    @field:Column(name = "total_episodes")
    var totalEpisodes: Int? = null,

    @field:Column(name = "air_weekday")
    var airWeekday: Int? = null,

    @field:Column(name = "cover_url", length = 500)
    var coverUrl: String? = null,

    // 时间信息（来自 BangumiData）
    @field:Column(name = "begin_time")
    var beginTime: Instant? = null,

    @field:Column(name = "end_time")
    var endTime: Instant? = null,

    @field:Column(name = "broadcast_period", length = 20)
    var broadcastPeriod: String? = null,

    // 是否有 BangumiData 数据（用于判断是否可以使用精确时间）
    @field:Column(name = "has_bangumi_data")
    var hasBangumiData: Boolean = false
) : TimestampedEntity() {

    // 播放平台列表（来自 BangumiData）
    @field:ElementCollection(fetch = FetchType.EAGER)
    @field:CollectionTable(
        name = "anime_platforms",
        joinColumns = [JoinColumn(name = "anime_id")]
    )
    var platforms: MutableList<AnimePlatform> = mutableListOf()
}

/**
 * 播放平台信息（嵌入式）
 */
@Embeddable
class AnimePlatform(
    @field:Column(name = "name", nullable = false)
    var name: String = "",

    @field:Column(name = "url", nullable = false, length = 500)
    var url: String = "",

    @field:Column(name = "regions")
    var regions: String? = null  // 用逗号分隔的地区列表，如 "CN,TW,HK"
) {
    constructor() : this("", "", null)
}
