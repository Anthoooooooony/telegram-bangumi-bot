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
class ImageGeneratorService(
    private val bangumiCacheService: BangumiCacheService
) {
    private val log = LoggerFactory.getLogger(ImageGeneratorService::class.java)

    companion object {
        private const val CARD_WIDTH = 400
        private const val CARD_HEIGHT = 200

        // 颜色定义
        private val ACCENT_COLOR = Color(255, 107, 107)        // 新剧集通知 - 红色
        private val SUMMARY_ACCENT_COLOR = Color(100, 180, 255) // 每日汇总 - 蓝色
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

    /**
     * 配置 Graphics2D 渲染提示，提升绘制质量
     */
    private fun setupRenderingHints(g2d: Graphics2D) {
        // 开启图形抗锯齿，使线条和形状边缘更平滑
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        // 开启文字抗锯齿，使字体渲染更清晰
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        // 双三次插值
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
    }

    private fun drawBackground(g2d: Graphics2D, coverUrl: String?) {
        if (!coverUrl.isNullOrBlank()) {
            try {
                val coverImage = loadCoverImage(coverUrl)
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
     * 生成每日汇总卡片图片
     * 每个番剧条目带有封面背景和渐变效果
     */
    fun generateDailySummaryCard(animes: List<DailySummaryAnime>): ByteArray {
        log.debug("生成每日汇总图片: count={}", animes.size)

        // 动态计算卡片高度
        val headerHeight = 60          // 标题区域
        val itemHeight = 70            // 每个番剧条目高度（增大以显示封面）
        val itemGap = 8                // 条目间距
        val footerHeight = 35          // 底部留白
        val maxItems = 16               // 最多显示16部
        val displayCount = minOf(animes.size, maxItems)
        val cardHeight = headerHeight + displayCount * (itemHeight + itemGap) + footerHeight +
            if (animes.size > maxItems) 30 else 0

        val image = BufferedImage(CARD_WIDTH, cardHeight, BufferedImage.TYPE_INT_ARGB)
        val g2d = image.createGraphics()

        try {
            setupRenderingHints(g2d)

            // 绘制纯色背景
            g2d.color = FALLBACK_BG
            g2d.fillRect(0, 0, CARD_WIDTH, cardHeight)

            // 绘制内容
            drawDailySummaryContent(g2d, animes, cardHeight, maxItems, itemHeight, itemGap, headerHeight)

        } finally {
            g2d.dispose()
        }

        val outputStream = ByteArrayOutputStream()
        ImageIO.write(image, "PNG", outputStream)
        return outputStream.toByteArray()
    }

    private fun drawDailySummaryContent(
        g2d: Graphics2D,
        animes: List<DailySummaryAnime>,
        cardHeight: Int,
        maxItems: Int,
        itemHeight: Int,
        itemGap: Int,
        headerHeight: Int
    ) {
        val margin = 15
        var y = 25

        // 标题行
        g2d.color = SUMMARY_ACCENT_COLOR
        g2d.font = getFont(Font.BOLD, 16)
        g2d.drawString("今日追番更新", margin, y)

        // 数量（右侧）
        g2d.color = TEXT_MUTED
        g2d.font = getFont(Font.PLAIN, 14)
        val countText = "${animes.size} 部"
        val countWidth = g2d.fontMetrics.stringWidth(countText)
        g2d.drawString(countText, CARD_WIDTH - margin - countWidth, y)

        y = headerHeight

        // 番剧列表
        val displayAnimes = animes.take(maxItems)

        displayAnimes.forEachIndexed { index, anime ->
            drawAnimeItem(g2d, anime, margin, y, CARD_WIDTH - margin * 2, itemHeight, index + 1)
            y += itemHeight + itemGap
        }

        // 如果有更多番剧，显示省略提示
        if (animes.size > maxItems) {
            g2d.color = TEXT_MUTED
            g2d.font = getFont(Font.PLAIN, 13)
            g2d.drawString("... 还有 ${animes.size - maxItems} 部番剧", margin, y + 15)
        }

        // 时间戳
        g2d.color = TEXT_MUTED
        g2d.font = getFont(Font.PLAIN, 11)
        val timeText = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        val timeWidth = g2d.fontMetrics.stringWidth(timeText)
        g2d.drawString(timeText, CARD_WIDTH - margin - timeWidth, cardHeight - 12)
    }

    /**
     * 绘制单个番剧条目（带封面背景和渐变）
     */
    private fun drawAnimeItem(g2d: Graphics2D, anime: DailySummaryAnime, x: Int, y: Int, width: Int, height: Int, index: Int) {
        val originalClip = g2d.clip
        val itemRadius = 12f

        // 创建圆角裁剪区域
        val itemRect = RoundRectangle2D.Float(x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat(), itemRadius, itemRadius)
        g2d.clip = itemRect

        // 尝试绘制封面背景（使用缓存）
        var hasCover = false
        if (!anime.coverUrl.isNullOrBlank()) {
            try {
                val coverImage = loadCoverImage(anime.coverUrl)
                if (coverImage != null) {
                    // Cover 模式：缩放填满条目区域
                    val scale = maxOf(width.toDouble() / coverImage.width, height.toDouble() / coverImage.height)
                    val scaledWidth = (coverImage.width * scale).toInt()
                    val scaledHeight = (coverImage.height * scale).toInt()
                    val drawX = x + (width - scaledWidth) / 2
                    val drawY = y + (height - scaledHeight) / 2
                    g2d.drawImage(coverImage, drawX, drawY, scaledWidth, scaledHeight, null)
                    hasCover = true
                }
            } catch (e: Exception) {
                log.debug("加载封面图失败: {}", e.message)
            }
        }

        // 如果没有封面，使用纯色背景
        if (!hasCover) {
            g2d.color = Color(45, 45, 60)
            g2d.fillRect(x, y, width, height)
        }

        // 绘制渐变遮罩（从左到右）
        val overlay = GradientPaint(
            x.toFloat(), y.toFloat(), Color(OVERLAY_DARK.red, OVERLAY_DARK.green, OVERLAY_DARK.blue, 230),
            (x + width * 0.7f), y.toFloat(), Color(OVERLAY_DARK.red, OVERLAY_DARK.green, OVERLAY_DARK.blue, if (hasCover) 100 else 180)
        )
        g2d.paint = overlay
        g2d.fillRect(x, y, width, height)

        // 恢复裁剪区域
        g2d.clip = originalClip

        // 绘制左侧装饰条
        g2d.color = SUMMARY_ACCENT_COLOR
        g2d.fillRoundRect(x, y, 4, height, 4, 4)

        // 绘制序号
        g2d.color = SUMMARY_ACCENT_COLOR
        g2d.font = getFont(Font.BOLD, 14)
        g2d.drawString("$index", x + 14, y + height / 2 + 5)

        // 文字内容区域
        val textX = x + 40
        val maxTextWidth = width - 55

        // 绘制番剧名（上半部分）
        g2d.color = TEXT_PRIMARY
        g2d.font = getFont(Font.BOLD, 16)
        val displayName = truncateText(g2d, anime.name, maxTextWidth)
        g2d.drawString(displayName, textX, y + 28)

        // 绘制更新信息（下半部分）
        if (!anime.airInfo.isNullOrBlank()) {
            g2d.color = TEXT_MUTED
            g2d.font = getFont(Font.PLAIN, 13)
            g2d.drawString(anime.airInfo, textX, y + 50)
        }
    }

    /**
     * 加载封面图片（优先从缓存获取）
     */
    private fun loadCoverImage(coverUrl: String): BufferedImage? {
        // 优先从缓存获取
        bangumiCacheService.getCoverImage(coverUrl)?.let { return it }

        // 缓存未命中，从网络加载
        return try {
            val url = URI(coverUrl).toURL()
            val connection = url.openConnection()
            connection.setRequestProperty("User-Agent", "telegram-bangumi-bot/1.0")
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            val image = ImageIO.read(connection.getInputStream())
            if (image != null) {
                bangumiCacheService.putCoverImage(coverUrl, image)
            }
            image
        } catch (e: Exception) {
            log.debug("从网络加载封面图失败: {}", e.message)
            null
        }
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

    /**
     * 生成订阅列表卡片图片
     */
    fun generateSubscriptionListCard(animes: List<SubscriptionAnime>): ByteArray {
        log.debug("生成订阅列表图片: count={}", animes.size)

        // 动态计算卡片高度
        val headerHeight = 60
        val itemHeight = 70
        val itemGap = 8
        val footerHeight = 35
        val maxItems = 16
        val displayCount = minOf(animes.size, maxItems)
        val cardHeight = headerHeight + displayCount * (itemHeight + itemGap) + footerHeight +
            if (animes.size > maxItems) 30 else 0

        val image = BufferedImage(CARD_WIDTH, cardHeight, BufferedImage.TYPE_INT_ARGB)
        val g2d = image.createGraphics()

        try {
            setupRenderingHints(g2d)

            g2d.color = FALLBACK_BG
            g2d.fillRect(0, 0, CARD_WIDTH, cardHeight)

            drawSubscriptionListContent(g2d, animes, cardHeight, maxItems, itemHeight, itemGap, headerHeight)

        } finally {
            g2d.dispose()
        }

        val outputStream = ByteArrayOutputStream()
        ImageIO.write(image, "PNG", outputStream)
        return outputStream.toByteArray()
    }

    private fun drawSubscriptionListContent(
        g2d: Graphics2D,
        animes: List<SubscriptionAnime>,
        cardHeight: Int,
        maxItems: Int,
        itemHeight: Int,
        itemGap: Int,
        headerHeight: Int
    ) {
        val margin = 15
        var y = 25

        // 标题行（使用绿色）
        val listAccentColor = Color(100, 200, 150)
        g2d.color = listAccentColor
        g2d.font = getFont(Font.BOLD, 16)
        g2d.drawString("追番列表", margin, y)

        // 数量（右侧）
        g2d.color = TEXT_MUTED
        g2d.font = getFont(Font.PLAIN, 14)
        val countText = "${animes.size} 部"
        val countWidth = g2d.fontMetrics.stringWidth(countText)
        g2d.drawString(countText, CARD_WIDTH - margin - countWidth, y)

        y = headerHeight

        // 番剧列表
        val displayAnimes = animes.take(maxItems)

        displayAnimes.forEachIndexed { index, anime ->
            drawSubscriptionItem(g2d, anime, margin, y, CARD_WIDTH - margin * 2, itemHeight, index + 1, listAccentColor)
            y += itemHeight + itemGap
        }

        // 如果有更多番剧，显示省略提示
        if (animes.size > maxItems) {
            g2d.color = TEXT_MUTED
            g2d.font = getFont(Font.PLAIN, 13)
            g2d.drawString("... 还有 ${animes.size - maxItems} 部番剧", margin, y + 15)
        }

        // 时间戳
        g2d.color = TEXT_MUTED
        g2d.font = getFont(Font.PLAIN, 11)
        val timeText = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        val timeWidth = g2d.fontMetrics.stringWidth(timeText)
        g2d.drawString(timeText, CARD_WIDTH - margin - timeWidth, cardHeight - 12)
    }

    private fun drawSubscriptionItem(g2d: Graphics2D, anime: SubscriptionAnime, x: Int, y: Int, width: Int, height: Int, index: Int, accentColor: Color) {
        val originalClip = g2d.clip
        val itemRadius = 12f

        val itemRect = RoundRectangle2D.Float(x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat(), itemRadius, itemRadius)
        g2d.clip = itemRect

        // 尝试绘制封面背景（使用缓存）
        var hasCover = false
        if (!anime.coverUrl.isNullOrBlank()) {
            try {
                val coverImage = loadCoverImage(anime.coverUrl)
                if (coverImage != null) {
                    val scale = maxOf(width.toDouble() / coverImage.width, height.toDouble() / coverImage.height)
                    val scaledWidth = (coverImage.width * scale).toInt()
                    val scaledHeight = (coverImage.height * scale).toInt()
                    val drawX = x + (width - scaledWidth) / 2
                    val drawY = y + (height - scaledHeight) / 2
                    g2d.drawImage(coverImage, drawX, drawY, scaledWidth, scaledHeight, null)
                    hasCover = true
                }
            } catch (e: Exception) {
                log.debug("加载封面图失败: {}", e.message)
            }
        }

        if (!hasCover) {
            g2d.color = Color(45, 45, 60)
            g2d.fillRect(x, y, width, height)
        }

        // 渐变遮罩
        val overlay = GradientPaint(
            x.toFloat(), y.toFloat(), Color(OVERLAY_DARK.red, OVERLAY_DARK.green, OVERLAY_DARK.blue, 230),
            (x + width * 0.7f), y.toFloat(), Color(OVERLAY_DARK.red, OVERLAY_DARK.green, OVERLAY_DARK.blue, if (hasCover) 100 else 180)
        )
        g2d.paint = overlay
        g2d.fillRect(x, y, width, height)

        g2d.clip = originalClip

        // 左侧装饰条
        g2d.color = accentColor
        g2d.fillRoundRect(x, y, 4, height, 4, 4)

        // 序号
        g2d.color = accentColor
        g2d.font = getFont(Font.BOLD, 14)
        g2d.drawString("$index", x + 14, y + height / 2 + 5)

        // 文字内容
        val textX = x + 40
        val maxTextWidth = width - 55

        // 番剧名
        g2d.color = TEXT_PRIMARY
        g2d.font = getFont(Font.BOLD, 16)
        val displayName = truncateText(g2d, anime.name, maxTextWidth)
        g2d.drawString(displayName, textX, y + 28)

        // 集数信息：已播出 / 总集数
        g2d.color = TEXT_MUTED
        g2d.font = getFont(Font.PLAIN, 13)
        val epInfo = buildString {
            if (anime.latestAiredEp != null && anime.latestAiredEp > 0) {
                append("已播出 ${anime.latestAiredEp} 集")
                if (anime.totalEpisodes != null && anime.totalEpisodes > 0) {
                    append(" / 共 ${anime.totalEpisodes} 集")
                }
            } else if (anime.totalEpisodes != null && anime.totalEpisodes > 0) {
                append("共 ${anime.totalEpisodes} 集")
            }
        }
        if (epInfo.isNotEmpty()) {
            g2d.drawString(epInfo, textX, y + 50)
        }
    }
}

/**
 * 每日汇总番剧信息
 */
data class DailySummaryAnime(
    val name: String,
    val coverUrl: String? = null,
    val airInfo: String? = null  // 更新信息，如 "第 5 集" 或 "今日更新"
)

/**
 * 订阅列表番剧信息
 */
data class SubscriptionAnime(
    val name: String,
    val coverUrl: String? = null,
    val totalEpisodes: Int? = null,  // 总集数
    val latestAiredEp: Int? = null   // 当前已播出集数（使用 ep 本季集数）
)
