package `fun`.fantasea.bangumi.bot

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DynamicMessageTest {

    @Test
    fun `should add and build sections`() {
        var editedText = ""
        val msg = DynamicMessage(123L, 456) { _, _, text -> editedText = text }

        msg.section("header", "标题内容")
            .section("body", "正文内容")

        assertEquals("标题内容\n\n正文内容", msg.build())
    }

    @Test
    fun `should update existing section`() {
        var editedText = ""
        val msg = DynamicMessage(123L, 456) { _, _, text -> editedText = text }

        msg.section("status", "加载中...")
            .section("status", "加载完成")

        assertEquals("加载完成", msg.build())
    }

    @Test
    fun `should remove section`() {
        var editedText = ""
        val msg = DynamicMessage(123L, 456) { _, _, text -> editedText = text }

        msg.section("header", "标题")
            .section("temp", "临时内容")
            .section("footer", "底部")
            .removeSection("temp")

        assertEquals("标题\n\n底部", msg.build())
    }

    @Test
    fun `should call editFn on update`() {
        var capturedChatId = 0L
        var capturedMessageId = 0
        var capturedText = ""

        val msg = DynamicMessage(123L, 456) { chatId, messageId, text ->
            capturedChatId = chatId
            capturedMessageId = messageId
            capturedText = text
        }

        msg.section("content", "测试内容").update()

        assertEquals(123L, capturedChatId)
        assertEquals(456, capturedMessageId)
        assertEquals("测试内容", capturedText)
    }

    @Test
    fun `should support fluent api chaining`() {
        var updateCount = 0
        val msg = DynamicMessage(1L, 1) { _, _, _ -> updateCount++ }

        val result = msg
            .section("a", "内容A")
            .section("b", "内容B")
            .update()
            .section("b", "更新后的B")
            .update()

        assertSame(msg, result)
        assertEquals(2, updateCount)
    }

    @Test
    fun `should preserve section order`() {
        var editedText = ""
        val msg = DynamicMessage(1L, 1) { _, _, text -> editedText = text }

        msg.section("first", "1")
            .section("second", "2")
            .section("third", "3")
            .section("second", "2-updated")  // 更新不改变顺序

        assertEquals("1\n\n2-updated\n\n3", msg.build())
    }
}
