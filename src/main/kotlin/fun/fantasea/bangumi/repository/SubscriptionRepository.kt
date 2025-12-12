package `fun`.fantasea.bangumi.repository

import `fun`.fantasea.bangumi.entity.Subscription
import `fun`.fantasea.bangumi.entity.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface SubscriptionRepository : JpaRepository<Subscription, Long> {

    fun findByUserAndSubjectId(user: User, subjectId: Int): Subscription?

    fun findByUserTelegramId(telegramId: Long): List<Subscription>

    /**
     * 查询所有有下次通知时间的订阅（用于重启恢复）
     */
    @Query("SELECT s FROM Subscription s WHERE s.nextNotifyTime IS NOT NULL")
    fun findAllWithNextNotifyTime(): List<Subscription>
}
