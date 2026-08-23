package com.ali.menbaradkshk.data

import org.junit.Assert.assertEquals
import org.junit.Test

/// اختبار منطق دمج المزامنة التفاضليّة وحده — بلا شبكة ولا Firestore.
class MergeByIdTest {

    private data class Item(val id: String, val name: String)

    private fun merge(
        base: List<Item>,
        changed: List<Item> = emptyList(),
        deleted: Set<String> = emptySet(),
    ) = mergeById(base, changed, deleted, Item::id)

    @Test
    fun `المعدَّل يحلّ مكانه بلا تغيير ترتيب الكاش`() {
        val base = listOf(Item("a", "أ"), Item("b", "ب"), Item("c", "ج"))
        val result = merge(base, changed = listOf(Item("b", "ب المصحَّح")))
        assertEquals(listOf("a", "b", "c"), result.map(Item::id))
        assertEquals("ب المصحَّح", result[1].name)
    }

    @Test
    fun `الجديد يُلحق في الآخر`() {
        val result = merge(listOf(Item("a", "أ")), changed = listOf(Item("z", "ز")))
        assertEquals(listOf("a", "z"), result.map(Item::id))
    }

    @Test
    fun `المحذوف يسقط ولو ورد في التغييرات`() {
        val base = listOf(Item("a", "أ"), Item("b", "ب"))
        val result = merge(
            base,
            changed = listOf(Item("b", "ب بعد تعديل")),
            deleted = setOf("b"),
        )
        assertEquals(listOf("a"), result.map(Item::id))
    }

    @Test
    fun `لا تكرار حين يتكرّر المعرّف في التغييرات`() {
        val result = merge(
            listOf(Item("a", "أ")),
            changed = listOf(Item("a", "أ١"), Item("a", "أ٢")),
        )
        assertEquals(1, result.size)
        // الأحدث يغلب: `associateBy` تُبقي آخر قيمة لكل معرّف.
        assertEquals("أ٢", result.first().name)
    }

    @Test
    fun `لا تغييرات يعني الكاش كما هو`() {
        val base = listOf(Item("a", "أ"), Item("b", "ب"))
        assertEquals(base, merge(base))
    }
}
