package `fun`.fantasea.bangumi.entity

import jakarta.persistence.*

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
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    var user: User,

    // Bangumi subject ID
    @Column(name = "subject_id", nullable = false)
    var subjectId: Int,

    // 番剧名称
    @Column(name = "subject_name", nullable = false)
    var subjectName: String,

    // 中文名称
    @Column(name = "subject_name_cn")
    var subjectNameCn: String? = null,

    // 总集数
    @Column(name = "total_episodes")
    var totalEpisodes: Int? = null,

    // 放送星期 (1-7)
    @Column(name = "air_weekday")
    var airWeekday: Int? = null,

    // 最新已播出集数 (本地追踪)
    @Column(name = "latest_aired_ep")
    var latestAiredEp: Int = 0,

    // 最后通知的集数
    @Column(name = "last_notified_ep")
    var lastNotifiedEp: Int = 0
) : TimestampedEntity()
