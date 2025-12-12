package `fun`.fantasea.bangumi.repository

import `fun`.fantasea.bangumi.entity.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Repository
import java.time.LocalTime
import kotlin.reflect.KProperty1

@Repository
interface UserRepository : JpaRepository<User, Long>, JpaSpecificationExecutor<User> {
    fun findByTelegramId(telegramId: Long): User?
}

/** 获取属性名称，提供编译时安全性 */
private val <T, V> KProperty1<T, V>.n: String get() = name

object UserSpecifications {
    fun dailySummaryEnabled(): Specification<User> = Specification { root, _, cb ->
        cb.equal(root.get<Boolean>(User::dailySummaryEnabled.n), true)
    }

    fun hasBangumiToken(): Specification<User> = Specification { root, _, cb ->
        cb.isNotNull(root.get<String>(User::bangumiToken.n))
    }

    fun idNotIn(excludeIds: Set<Long>): Specification<User> = Specification { root, _, cb ->
        if (excludeIds.isEmpty()) {
            cb.conjunction()
        } else {
            cb.not(root.get<Long>(User::id.n).`in`(excludeIds))
        }
    }

    fun dailySummaryTimeMatches(time: LocalTime): Specification<User> = Specification { root, _, cb ->
        // 截断到分钟级别进行比较，避免秒数差异
        val truncatedTime = time.withSecond(0).withNano(0)
        cb.equal(root.get<LocalTime>(User::dailySummaryTime.n), truncatedTime)
    }
}
