package `fun`.fantasea.bangumi

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaAuditing
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
@EnableJpaAuditing
class BangumiApplication

fun main(args: Array<String>) {
    runApplication<BangumiApplication>(*args)
}
