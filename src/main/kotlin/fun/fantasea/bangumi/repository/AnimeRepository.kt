package `fun`.fantasea.bangumi.repository

import `fun`.fantasea.bangumi.entity.Anime
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface AnimeRepository : JpaRepository<Anime, Int> {
}
