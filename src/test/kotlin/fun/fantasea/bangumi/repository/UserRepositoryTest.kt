package `fun`.fantasea.bangumi.repository

import `fun`.fantasea.bangumi.entity.User
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.test.context.ActiveProfiles

/**
 * 测试前需要先启动 PostgreSQL:
 * docker-compose up -d db
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserRepositoryTest {

    @Autowired
    lateinit var userRepository: UserRepository

    @Test
    fun `should save and find user by telegram id`() {
        // given
        val user = User(telegramId = 123456789L, telegramUsername = "testuser")

        // when
        val saved = userRepository.save(user)
        val found = userRepository.findByTelegramId(123456789L)

        // then
        assertNotNull(saved.id)
        assertNotNull(found)
        assertEquals(123456789L, found!!.telegramId)
        assertEquals("testuser", found.telegramUsername)
    }
}
