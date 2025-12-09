package `fun`.fantasea.bangumi.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Token 加密解密服务
 * 使用 AES-GCM 加密算法
 */
@Service
class TokenService(
    @Value("\${encryption.key}") private val encryptionKey: String
) {
    companion object {
        private const val ALGORITHM = "AES/GCM/NoPadding"
        private const val KEY_ALGORITHM = "AES"
        private const val GCM_IV_LENGTH = 12  // GCM 推荐 IV 长度
        private const val GCM_TAG_LENGTH = 128 // 认证标签长度 (bits)
    }

    private val secretKey: SecretKeySpec by lazy {
        // 将密钥填充或截断到 32 字节 (256 位)
        val keyBytes = encryptionKey.toByteArray(Charsets.UTF_8)
        val paddedKey = ByteArray(32)
        System.arraycopy(keyBytes, 0, paddedKey, 0, minOf(keyBytes.size, 32))
        SecretKeySpec(paddedKey, KEY_ALGORITHM)
    }

    /**
     * 加密 token
     * @param plainText 明文 token
     * @return Base64 编码的密文 (IV + 密文)
     */
    fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(ALGORITHM)

        // 生成随机 IV
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)

        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)

        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        // 将 IV 和密文拼接在一起
        val combined = ByteArray(iv.size + cipherText.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)

        return Base64.getEncoder().encodeToString(combined)
    }

    /**
     * 解密 token
     * @param encryptedText Base64 编码的密文
     * @return 解密后的明文 token
     */
    fun decrypt(encryptedText: String): String {
        val combined = Base64.getDecoder().decode(encryptedText)

        // 提取 IV 和密文
        val iv = ByteArray(GCM_IV_LENGTH)
        val cipherText = ByteArray(combined.size - GCM_IV_LENGTH)
        System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH)
        System.arraycopy(combined, GCM_IV_LENGTH, cipherText, 0, cipherText.size)

        val cipher = Cipher.getInstance(ALGORITHM)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

        val plainText = cipher.doFinal(cipherText)
        return String(plainText, Charsets.UTF_8)
    }
}
