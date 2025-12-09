package `fun`.fantasea.bangumi.repository

import `fun`.fantasea.bangumi.entity.Subscription
import `fun`.fantasea.bangumi.entity.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface SubscriptionRepository : JpaRepository<Subscription, Long> {

    fun findByUserAndSubjectId(user: User, subjectId: Int): Subscription?

    fun findByUserTelegramId(telegramId: Long): List<Subscription>
}
