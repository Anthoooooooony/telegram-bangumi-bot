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
        private const val ITEM_RADIUS = 12f

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

    /** 颜色定义 */
    private object Colors {
        val ACCENT = Color(255, 107, 107)           // 新剧集通知 - 红色
        val SUMMARY_ACCENT = Color(100, 180, 255)   // 每日汇总 - 蓝色
        val TEXT_PRIMARY = Color.WHITE
        val TEXT_SECONDARY = Color(220, 220, 230)
        val TEXT_MUTED = Color(160, 160, 180)
        val OVERLAY_DARK = Color(20, 20, 30)
        val FALLBACK_BG = Color(30, 30, 45)
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
        // 无封面 URL 时 fallback
        if (coverUrl.isNullOrBlank()) {
            drawFallbackBackground(g2d)
            return
        }

        // 尝试加载封面图
        val coverImage = try {
            loadCoverImage(coverUrl)
        } catch (e: Exception) {
            log.debug("加载封面图失败: {}", e.message)
            null
        }

        // 封面加载失败时 fallback
        if (coverImage == null) {
            drawFallbackBackground(g2d)
            return
        }

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
    }

    private fun drawFallbackBackground(g2d: Graphics2D) {
        g2d.color = Colors.FALLBACK_BG
        g2d.fillRect(0, 0, CARD_WIDTH, CARD_HEIGHT)
    }

    private fun drawOverlay(g2d: Graphics2D) {
        // 左到右渐变遮罩
        val overlay = GradientPaint(
            0f,
            0f,
            Color(Colors.OVERLAY_DARK.red, Colors.OVERLAY_DARK.green, Colors.OVERLAY_DARK.blue, 240),
            CARD_WIDTH * 0.7f,
            0f,
            Color(Colors.OVERLAY_DARK.red, Colors.OVERLAY_DARK.green, Colors.OVERLAY_DARK.blue, 160)
        )
        g2d.paint = overlay
        g2d.fillRect(0, 0, CARD_WIDTH, CARD_HEIGHT)

        // 底部额外加深
        val bottomOverlay = GradientPaint(
            0f,
            CARD_HEIGHT * 0.6f,
            Color(0, 0, 0, 0),
            0f,
            CARD_HEIGHT.toFloat(),
            Color(0, 0, 0, 150)
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
        g2d.color = Colors.ACCENT
        g2d.fillRoundRect(0, 0, 5, CARD_HEIGHT, 5, 5)

        // 标题: 新剧集更新
        g2d.color = Colors.ACCENT
        g2d.font = getFont(Font.BOLD, 13)
        g2d.drawString("新剧集更新", leftMargin, y)
        y += 40

        // 番剧名称
        g2d.color = Colors.TEXT_PRIMARY
        g2d.font = getFont(Font.BOLD, 24)
        val displayName = truncateText(g2d, animeName, CARD_WIDTH - leftMargin * 2)
        g2d.drawString(displayName, leftMargin, y)
        y += 30

        // 集数
        g2d.color = Colors.TEXT_SECONDARY
        g2d.font = getFont(Font.PLAIN, 16)
        g2d.drawString(episodeText, leftMargin, y)
        y += 25

        // 剧集名（如果有）
        if (!episodeName.isNullOrBlank()) {
            g2d.color = Colors.TEXT_MUTED
            g2d.font = getFont(Font.PLAIN, 14)
            val displayEpName = truncateText(g2d, episodeName, CARD_WIDTH - leftMargin * 2)
            g2d.drawString(displayEpName, leftMargin, y)
        }

        // 播放平台
        g2d.color = Colors.TEXT_MUTED
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
            g2d.color = Colors.FALLBACK_BG
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
        g2d.color = Colors.SUMMARY_ACCENT
        g2d.font = getFont(Font.BOLD, 16)
        g2d.drawString("今日追番更新", margin, y)

        // 数量（右侧）
        g2d.color = Colors.TEXT_MUTED
        g2d.font = getFont(Font.PLAIN, 14)
        val countText = "${animes.size} 部"
        val countWidth = g2d.fontMetrics.stringWidth(countText)
        g2d.drawString(countText, CARD_WIDTH - margin - countWidth, y)

        y = headerHeight

        // 番剧列表
        val displayAnimes = animes.take(maxItems)

        displayAnimes.forEachIndexed { index, anime ->
            drawListItem(
                g2d = g2d,
                x = margin,
                y = y,
                width = CARD_WIDTH - margin * 2,
                height = itemHeight,
                index = index + 1,
                accentColor = Colors.SUMMARY_ACCENT,
                name = anime.name,
                coverUrl = anime.coverUrl,
                infoText = anime.airInfo
            )
            y += itemHeight + itemGap
        }

        // 如果有更多番剧，显示省略提示
        if (animes.size > maxItems) {
            g2d.color = Colors.TEXT_MUTED
            g2d.font = getFont(Font.PLAIN, 13)
            g2d.drawString("... 还有 ${animes.size - maxItems} 部番剧", margin, y + 15)
        }

        // 时间戳
        g2d.color = Colors.TEXT_MUTED
        g2d.font = getFont(Font.PLAIN, 11)
        val timeText = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        val timeWidth = g2d.fontMetrics.stringWidth(timeText)
        g2d.drawString(timeText, CARD_WIDTH - margin - timeWidth, cardHeight - 12)
    }

    /**
     * 绘制单个列表条目（通用方法，带封面背景和渐变）
     */
    private fun drawListItem(
        g2d: Graphics2D,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        index: Int,
        accentColor: Color,
        name: String,
        coverUrl: String?,
        infoText: String?
    ) {
        val originalClip = g2d.clip

        // 创建圆角裁剪区域
        val itemRect = RoundRectangle2D.Float(
            x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat(), ITEM_RADIUS, ITEM_RADIUS
        )
        g2d.clip = itemRect

        // 尝试绘制封面背景
        val hasCover = drawItemCover(g2d, coverUrl, x, y, width, height)

        // 如果没有封面，使用纯色背景
        if (!hasCover) {
            g2d.color = Color(45, 45, 60)
            g2d.fillRect(x, y, width, height)
        }

        // 绘制渐变遮罩
        val overlay = GradientPaint(
            x.toFloat(), y.toFloat(),
            Color(Colors.OVERLAY_DARK.red, Colors.OVERLAY_DARK.green, Colors.OVERLAY_DARK.blue, 230),
            (x + width * 0.7f), y.toFloat(),
            Color(Colors.OVERLAY_DARK.red, Colors.OVERLAY_DARK.green, Colors.OVERLAY_DARK.blue, if (hasCover) 100 else 180)
        )
        g2d.paint = overlay
        g2d.fillRect(x, y, width, height)

        g2d.clip = originalClip

        // 绘制左侧装饰条
        g2d.color = accentColor
        g2d.fillRoundRect(x, y, 4, height, 4, 4)

        // 绘制序号
        g2d.color = accentColor
        g2d.font = getFont(Font.BOLD, 14)
        g2d.drawString("$index", x + 14, y + height / 2 + 5)

        // 文字内容区域
        val textX = x + 40
        val maxTextWidth = width - 55

        // 绘制名称
        g2d.color = Colors.TEXT_PRIMARY
        g2d.font = getFont(Font.BOLD, 16)
        g2d.drawString(truncateText(g2d, name, maxTextWidth), textX, y + 28)

        // 绘制信息文本
        if (!infoText.isNullOrBlank()) {
            g2d.color = Colors.TEXT_MUTED
            g2d.font = getFont(Font.PLAIN, 13)
            g2d.drawString(infoText, textX, y + 50)
        }
    }

    /**
     * 绘制条目封面背景
     * @return 是否成功绘制封面
     */
    private fun drawItemCover(g2d: Graphics2D, coverUrl: String?, x: Int, y: Int, width: Int, height: Int): Boolean {
        if (coverUrl.isNullOrBlank()) return false

        return try {
            val coverImage = loadCoverImage(coverUrl) ?: return false
            val scale = maxOf(width.toDouble() / coverImage.width, height.toDouble() / coverImage.height)
            val scaledWidth = (coverImage.width * scale).toInt()
            val scaledHeight = (coverImage.height * scale).toInt()
            val drawX = x + (width - scaledWidth) / 2
            val drawY = y + (height - scaledHeight) / 2
            g2d.drawImage(coverImage, drawX, drawY, scaledWidth, scaledHeight, null)
            true
        } catch (e: Exception) {
            log.debug("加载封面图失败: {}", e.message)
            false
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

            g2d.color = Colors.FALLBACK_BG
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
        g2d.color = Colors.TEXT_MUTED
        g2d.font = getFont(Font.PLAIN, 14)
        val countText = "${animes.size} 部"
        val countWidth = g2d.fontMetrics.stringWidth(countText)
        g2d.drawString(countText, CARD_WIDTH - margin - countWidth, y)

        y = headerHeight

        // 番剧列表
        val displayAnimes = animes.take(maxItems)

        displayAnimes.forEachIndexed { index, anime ->
            val infoText = buildEpisodeInfo(anime.latestAiredEp, anime.totalEpisodes)
            drawListItem(
                g2d = g2d,
                x = margin,
                y = y,
                width = CARD_WIDTH - margin * 2,
                height = itemHeight,
                index = index + 1,
                accentColor = listAccentColor,
                name = anime.name,
                coverUrl = anime.coverUrl,
                infoText = infoText
            )
            y += itemHeight + itemGap
        }

        // 如果有更多番剧，显示省略提示
        if (animes.size > maxItems) {
            g2d.color = Colors.TEXT_MUTED
            g2d.font = getFont(Font.PLAIN, 13)
            g2d.drawString("... 还有 ${animes.size - maxItems} 部番剧", margin, y + 15)
        }

        // 时间戳
        g2d.color = Colors.TEXT_MUTED
        g2d.font = getFont(Font.PLAIN, 11)
        val timeText = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        val timeWidth = g2d.fontMetrics.stringWidth(timeText)
        g2d.drawString(timeText, CARD_WIDTH - margin - timeWidth, cardHeight - 12)
    }

    /**
     * 构建集数信息文本
     */
    private fun buildEpisodeInfo(latestAiredEp: Int?, totalEpisodes: Int?): String? {
        return buildString {
            if (latestAiredEp != null && latestAiredEp > 0) {
                append("已播出 $latestAiredEp 集")
                if (totalEpisodes != null && totalEpisodes > 0) {
                    append(" / 共 $totalEpisodes 集")
                }
            } else if (totalEpisodes != null && totalEpisodes > 0) {
                append("共 $totalEpisodes 集")
            }
        }.takeIf { it.isNotEmpty() }
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
