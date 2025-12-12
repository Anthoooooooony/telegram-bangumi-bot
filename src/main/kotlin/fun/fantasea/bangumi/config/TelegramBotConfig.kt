package `fun`.fantasea.bangumi.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.OkHttpClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Telegram Bot 配置，支持 HTTP 代理
 */
@Configuration
class TelegramBotConfig(
    @Value("\${telegram.proxy.host:}") private val proxyHost: String,
    @Value("\${telegram.proxy.port:0}") private val proxyPort: Int
) {

    @Bean
    fun telegramBotsApplication(jacksonObjectMapper: ObjectMapper): TelegramBotsLongPollingApplication {
        // Long polling 的 GetUpdates 默认超时 50 秒，readTimeout 需大于此值
        val httpClientBuilder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(75, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)

        // 配置代理
        if (proxyHost.isNotBlank() && proxyPort > 0) {
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort))
            httpClientBuilder.proxy(proxy)
        }

        return TelegramBotsLongPollingApplication({ jacksonObjectMapper() }, { httpClientBuilder.build() })
    }
}
