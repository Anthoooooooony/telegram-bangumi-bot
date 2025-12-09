package `fun`.fantasea.bangumi.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * TokenService 测试
 * 验证 token 加密解密功能
 */
class TokenServiceTest {

    private val tokenService = TokenService(encryptionKey = "test-encryption-key-32-chars!!")

    @Test
    fun `should encrypt and decrypt token correctly`() {
        // given
        val originalToken = "my-bangumi-token-12345"

        // when
        val encrypted = tokenService.encrypt(originalToken)
        val decrypted = tokenService.decrypt(encrypted)

        // then
        assertNotEquals(originalToken, encrypted, "加密后的 token 不应该和原始 token 相同")
        assertEquals(originalToken, decrypted, "解密后应该得到原始 token")
    }

    @Test
    fun `should produce different ciphertext for same plaintext`() {
        // given
        val token = "my-bangumi-token"

        // when
        val encrypted1 = tokenService.encrypt(token)
        val encrypted2 = tokenService.encrypt(token)

        // then
        // 由于使用随机 IV，相同明文应该产生不同密文
        assertNotEquals(encrypted1, encrypted2, "每次加密应该产生不同的密文")

        // 但解密后都应该得到相同的原文
        assertEquals(token, tokenService.decrypt(encrypted1))
        assertEquals(token, tokenService.decrypt(encrypted2))
    }

    @Test
    fun `should handle empty token`() {
        // given
        val emptyToken = ""

        // when
        val encrypted = tokenService.encrypt(emptyToken)
        val decrypted = tokenService.decrypt(encrypted)

        // then
        assertEquals(emptyToken, decrypted)
    }

    @Test
    fun `should handle long token`() {
        // given
        val longToken = "a".repeat(500)

        // when
        val encrypted = tokenService.encrypt(longToken)
        val decrypted = tokenService.decrypt(encrypted)

        // then
        assertEquals(longToken, decrypted)
    }
}
