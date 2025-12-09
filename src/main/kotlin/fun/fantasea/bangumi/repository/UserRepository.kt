package `fun`.fantasea.bangumi.repository

import `fun`.fantasea.bangumi.entity.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface UserRepository : JpaRepository<User, Long> {
    fun findByTelegramId(telegramId: Long): User?
}
