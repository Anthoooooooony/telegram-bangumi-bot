package `fun`.fantasea.bangumi.service

import `fun`.fantasea.bangumi.client.PlatformInfo
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.awt.*
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO

/**
 * 图片生成服务
 * 使用 Java2D 绘制通知卡片
 */
@Service
class ImageGeneratorService {
    private val log = LoggerFactory.getLogger(ImageGeneratorService::class.java)

    companion object {
        private const val CARD_WIDTH = 400
        private const val CARD_HEIGHT = 200
        private const val CORNER_RADIUS = 20f

        // 颜色定义
        private val ACCENT_COLOR = Color(255, 107, 107)
        private val TEXT_PRIMARY = Color.WHITE
        private val TEXT_SECONDARY = Color(220, 220, 230)
        private val TEXT_MUTED = Color(160, 160, 180)
        private val OVERLAY_DARK = Color(20, 20, 30)
        private val FALLBACK_BG = Color(30, 30, 45)

        // 优先使用的中文字体列表
        private val PREFERRED_FONTS = listOf(
            "Microsoft YaHei",      // Windows
            "PingFang SC",          // macOS
            "Noto Sans CJK SC",     // Linux
            "WenQuanYi Micro Hei",  // Linux
            "Source Han Sans SC",   // Adobe
            "Hiragino Sans GB",     // macOS
            "SimHei",               // Windows fallback
            "SansSerif"             // 最终 fallback
        )
    }

    // 缓存可用的字体名称，避免每次调用都扫描系统字体
    private val cachedFontName: String by lazy {
        val availableFonts = GraphicsEnvironment.getLocalGraphicsEnvironment()
            .availableFontFamilyNames.toSet()
        PREFERRED_FONTS.firstOrNull { it in availableFonts } ?: "SansSerif"
    }

    /**
     * 生成通知卡片图片
     */
    fun generateNotificationCard(
        animeName: String,
        episodeText: String,
        episodeName: String? = null,
        coverUrl: String? = null,
        platforms: List<PlatformInfo> = emptyList()
    ): ByteArray {
        log.debug("生成通知图片: anime={}, episode={}", animeName, episodeText)

        val image = BufferedImage(CARD_WIDTH, CARD_HEIGHT, BufferedImage.TYPE_INT_ARGB)
        val g2d = image.createGraphics()

        try {
            setupRenderingHints(g2d)

            // 创建圆角裁剪区域
            val roundRect = RoundRectangle2D.Float(0f, 0f, CARD_WIDTH.toFloat(), CARD_HEIGHT.toFloat(), CORNER_RADIUS, CORNER_RADIUS)
            g2d.clip = roundRect

            // 绘制背景（封面图或纯色）
            drawBackground(g2d, coverUrl)

            // 绘制渐变遮罩
            drawOverlay(g2d)

            // 绘制内容
            drawContent(g2d, animeName, episodeText, episodeName, platforms)

        } finally {
            g2d.dispose()
        }

        // 转换为 PNG 字节数组
        val outputStream = ByteArrayOutputStream()
        ImageIO.write(image, "PNG", outputStream)
        return outputStream.toByteArray()
    }

    private fun setupRenderingHints(g2d: Graphics2D) {
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    }

    private fun drawBackground(g2d: Graphics2D, coverUrl: String?) {
        if (!coverUrl.isNullOrBlank()) {
            try {
                val coverImage = ImageIO.read(URI(coverUrl).toURL())
                if (coverImage != null) {
                    // Cover 模式：缩放填满卡片
                    val scale = maxOf(
                        CARD_WIDTH.toDouble() / coverImage.width,
                        CARD_HEIGHT.toDouble() / coverImage.height
                    )
                    val scaledWidth = (coverImage.width * scale).toInt()
                    val scaledHeight = (coverImage.height * scale).toInt()
                    val x = (CARD_WIDTH - scaledWidth) / 2
                    val y = (CARD_HEIGHT - scaledHeight) / 2
                    g2d.drawImage(coverImage, x, y, scaledWidth, scaledHeight, null)
                    return
                }
            } catch (e: Exception) {
                log.debug("加载封面图失败: {}", e.message)
            }
        }

        // 降级为纯色背景
        g2d.color = FALLBACK_BG
        g2d.fillRect(0, 0, CARD_WIDTH, CARD_HEIGHT)
    }

    private fun drawOverlay(g2d: Graphics2D) {
        // 左到右渐变遮罩
        val overlay = GradientPaint(
            0f, 0f, Color(OVERLAY_DARK.red, OVERLAY_DARK.green, OVERLAY_DARK.blue, 240),
            CARD_WIDTH * 0.7f, 0f, Color(OVERLAY_DARK.red, OVERLAY_DARK.green, OVERLAY_DARK.blue, 160)
        )
        g2d.paint = overlay
        g2d.fillRect(0, 0, CARD_WIDTH, CARD_HEIGHT)

        // 底部额外加深
        val bottomOverlay = GradientPaint(
            0f, CARD_HEIGHT * 0.6f, Color(0, 0, 0, 0),
            0f, CARD_HEIGHT.toFloat(), Color(0, 0, 0, 150)
        )
        g2d.paint = bottomOverlay
        g2d.fillRect(0, 0, CARD_WIDTH, CARD_HEIGHT)
    }

    private fun drawContent(
        g2d: Graphics2D,
        animeName: String,
        episodeText: String,
        episodeName: String?,
        platforms: List<PlatformInfo>
    ) {
        val leftMargin = 20
        var y = 30

        // 左侧装饰条
        g2d.color = ACCENT_COLOR
        g2d.fillRoundRect(0, 0, 5, CARD_HEIGHT, 5, 5)

        // 标题: 新剧集更新
        g2d.color = ACCENT_COLOR
        g2d.font = getFont(Font.BOLD, 13)
        g2d.drawString("新剧集更新", leftMargin, y)
        y += 40

        // 番剧名称
        g2d.color = TEXT_PRIMARY
        g2d.font = getFont(Font.BOLD, 24)
        val displayName = truncateText(g2d, animeName, CARD_WIDTH - leftMargin * 2)
        g2d.drawString(displayName, leftMargin, y)
        y += 30

        // 集数
        g2d.color = TEXT_SECONDARY
        g2d.font = getFont(Font.PLAIN, 16)
        g2d.drawString(episodeText, leftMargin, y)
        y += 25

        // 剧集名（如果有）
        if (!episodeName.isNullOrBlank()) {
            g2d.color = TEXT_MUTED
            g2d.font = getFont(Font.PLAIN, 14)
            val displayEpName = truncateText(g2d, episodeName, CARD_WIDTH - leftMargin * 2)
            g2d.drawString(displayEpName, leftMargin, y)
        }

        // 播放平台
        g2d.color = TEXT_MUTED
        g2d.font = getFont(Font.PLAIN, 12)
        if (platforms.isNotEmpty()) {
            val platformText = platforms.joinToString(" · ") { it.name }
            val displayPlatforms = truncateText(g2d, platformText, CARD_WIDTH - leftMargin * 2)
            g2d.drawString(displayPlatforms, leftMargin, CARD_HEIGHT - 35)
        }

        // 时间
        val timeText = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        g2d.drawString(timeText, leftMargin, CARD_HEIGHT - 15)
    }

    /**
     * 获取字体，使用缓存的中文字体名称
     */
    private fun getFont(style: Int, size: Int): Font {
        return Font(cachedFontName, style, size)
    }

    /**
     * 截断文本以适应指定宽度
     */
    private fun truncateText(g2d: Graphics2D, text: String, maxWidth: Int): String {
        val metrics = g2d.fontMetrics
        if (metrics.stringWidth(text) <= maxWidth) {
            return text
        }

        var truncated = text
        while (truncated.isNotEmpty() && metrics.stringWidth("$truncated...") > maxWidth) {
            truncated = truncated.dropLast(1)
        }
        return "$truncated..."
    }
}
