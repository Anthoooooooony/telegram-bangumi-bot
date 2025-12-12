package `fun`.fantasea.bangumi.entity

import jakarta.persistence.*
import java.time.Instant

/**
 * 订阅记录
 * 存储用户在 Bangumi 上的追番数据
 */
@Entity
@Table(
    name = "subscriptions",
    uniqueConstraints = [UniqueConstraint(columnNames = ["user_id", "subject_id"])]
)
class Subscription(
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "user_id", nullable = false)
    var user: User,

    // 关联的番剧（渐进式迁移，暂时可空）
    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "anime_id")
    var anime: Anime? = null,

    // Bangumi subject ID（保留用于查询和迁移兼容）
    @field:Column(name = "subject_id", nullable = false)
    var subjectId: Int,

    // 以下字段为历史遗留，新数据通过 anime 关联获取
    // 番剧名称
    @field:Column(name = "subject_name", nullable = false)
    var subjectName: String,

    // 中文名称
    @field:Column(name = "subject_name_cn")
    var subjectNameCn: String? = null,

    // 总集数
    @field:Column(name = "total_episodes")
    var totalEpisodes: Int? = null,

    // 放送星期 (1-7)
    @field:Column(name = "air_weekday")
    var airWeekday: Int? = null,

    // 最新已播出集数 (本地追踪)
    @field:Column(name = "latest_aired_ep")
    var latestAiredEp: Int = 0,

    // 最后通知的集数
    @field:Column(name = "last_notified_ep")
    var lastNotifiedEp: Int = 0,

    // 下次通知时间（用于定时调度和重启恢复）
    @field:Column(name = "next_notify_time")
    var nextNotifyTime: Instant? = null,

    // 下次通知的集数
    @field:Column(name = "next_notify_ep")
    var nextNotifyEp: Int? = null
) : TimestampedEntity() {

    /**
     * 获取番剧显示名称（优先中文名）
     * 优先从关联的 Anime 获取，回退到本地字段
     */
    fun getDisplayName(): String {
        return anime
            ?.let { it.nameCn?.takeIf { cn -> cn.isNotBlank() } ?: it.name }
            ?: subjectNameCn?.takeIf { it.isNotBlank() }
            ?: subjectName
    }

    /**
     * 获取封面 URL
     */
    fun getCoverUrl(): String? { // todo 从缓存改为数据库持久化，从数据库获取
        return anime?.coverUrl
    }
}
