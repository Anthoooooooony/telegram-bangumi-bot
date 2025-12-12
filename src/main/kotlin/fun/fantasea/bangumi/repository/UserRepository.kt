package `fun`.fantasea.bangumi.repository

import `fun`.fantasea.bangumi.entity.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Repository
import java.time.LocalTime

@Repository
interface UserRepository : JpaRepository<User, Long>, JpaSpecificationExecutor<User> {
    fun findByTelegramId(telegramId: Long): User?
}

object UserSpecifications {
    fun dailySummaryEnabled(): Specification<User> = Specification { root, _, cb ->
        cb.equal(root.get<Boolean>("dailySummaryEnabled"), true)
    }

    fun hasBangumiToken(): Specification<User> = Specification { root, _, cb ->
        cb.isNotNull(root.get<String>("bangumiToken"))
    }

    fun idNotIn(excludeIds: Set<Long>): Specification<User> = Specification { root, _, cb ->
        if (excludeIds.isEmpty()) {
            cb.conjunction()
        } else {
            cb.not(root.get<Long>("id").`in`(excludeIds))
        }
    }

    fun dailySummaryTimeMatches(time: LocalTime): Specification<User> = Specification { root, _, cb ->
        val hourFn = cb.function("EXTRACT", Int::class.java, cb.literal("HOUR"), root.get<LocalTime>("dailySummaryTime"))
        val minuteFn = cb.function("EXTRACT", Int::class.java, cb.literal("MINUTE"), root.get<LocalTime>("dailySummaryTime"))
        cb.and(
            cb.equal(hourFn, time.hour),
            cb.equal(minuteFn, time.minute)
        )
    }
}
