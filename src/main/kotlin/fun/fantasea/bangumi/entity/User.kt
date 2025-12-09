package `fun`.fantasea.bangumi.entity

import jakarta.persistence.*
import java.time.LocalDateTime
import java.time.LocalTime

@Entity
@Table(name = "users")
class User(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "telegram_id", unique = true, nullable = false)
    var telegramId: Long,

    @Column(name = "telegram_username")
    var telegramUsername: String? = null,

    @Column(name = "bangumi_token", length = 512)
    var bangumiToken: String? = null,

    @Column(name = "bangumi_user_id")
    var bangumiUserId: Int? = null,

    @Column(name = "bangumi_username")
    var bangumiUsername: String? = null,

    @Column(name = "daily_summary_enabled")
    var dailySummaryEnabled: Boolean = true,

    @Column(name = "daily_summary_time")
    var dailySummaryTime: LocalTime = LocalTime.of(10, 0),

    @Column(name = "created_at")
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at")
    var updatedAt: LocalDateTime = LocalDateTime.now()
)
