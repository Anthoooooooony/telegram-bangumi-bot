package `fun`.fantasea.bangumi.config

import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.OkHttpClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * Telegram Bot 配置，支持 HTTP 代理
 */
@Configuration
class TelegramBotConfig(
    @Value("\${telegram.proxy.host:}") private val proxyHost: String,
    @Value("\${telegram.proxy.port:0}") private val proxyPort: Int
) {

    @Bean
    fun telegramBotsApplication(): TelegramBotsLongPollingApplication {
        val okHttpClient = if (proxyHost.isNotBlank() && proxyPort > 0) {
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort))
            OkHttpClient.Builder().proxy(proxy).build()
        } else {
            OkHttpClient()
        }
        return TelegramBotsLongPollingApplication({ ObjectMapper() }, { okHttpClient })
    }
}
