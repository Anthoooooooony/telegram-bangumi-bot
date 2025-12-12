package `fun`.fantasea.bangumi.bot

/**
 * 动态消息容器
 * 支持分段更新消息内容，与 Telegram editMessage API 配合使用
 *
 * 使用示例:
 * ```
 * val msg = DynamicMessage(chatId, messageId, ::editMessage)
 *     .section("header", "绑定成功！\nBangumi 用户: xxx")
 *     .section("status", "正在同步...")
 *     .update()
 *
 * // 稍后更新状态
 * msg.section("status", "同步完成！").update()
 * ```
 */
class DynamicMessage(
    private val chatId: Long,
    private val messageId: Int,
    private val editFn: (Long, Int, String) -> Unit
) {
    private val sections = linkedMapOf<String, String>()

    /**
     * 设置或更新指定段落
     */
    fun section(key: String, content: String): DynamicMessage {
        sections[key] = content
        return this
    }

    /**
     * 移除指定段落
     */
    fun removeSection(key: String): DynamicMessage {
        sections.remove(key)
        return this
    }

    /**
     * 构建完整消息文本
     */
    fun build(): String = sections.values.joinToString("\n\n")

    /**
     * 更新 Telegram 消息
     */
    fun update(): DynamicMessage {
        editFn(chatId, messageId, build())
        return this
    }
}
