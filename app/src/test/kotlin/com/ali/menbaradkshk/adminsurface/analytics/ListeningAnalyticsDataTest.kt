package com.ali.menbaradkshk.adminsurface.analytics

import android.app.Application
import com.ali.menbaradkshk.adminsurface.AdminWorkspaceApi
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], manifest = Config.NONE)
class ListeningAnalyticsDataTest {
    private val now = 1_790_000_000_000L
    private val dayMs = 86_400_000L

    private fun row(
        id: String, title: String, age: Long, views: Number, section: String = "s",
        deleted: Long? = null,
    ) = JSONObject().put("id", id).put("title", title).put("created_at_ms", now - age * dayMs)
        .put("views", views).put("subcategory_id", section).put("deleted_at_ms", deleted ?: JSONObject.NULL)
        .put("added_by", "not-for-display@example.org").put("device_id", "not-for-display")

    private fun section(id: String, name: String, deleted: Long? = null) =
        JSONObject().put("id", id).put("name", name).put("deleted_at_ms", deleted ?: JSONObject.NULL)

    private fun items(vararg rows: JSONObject) = JSONObject().put("items", JSONArray().apply { rows.forEach { put(it) } })

    @Test
    fun onlyExactReadEndpointsAndRecentCohortsUseAddedDate() = runBlocking {
        val paths = mutableListOf<String>()
        val fixture = items(
            row("old", "درس قديم", 90, 400),
            row("today", "درس حديث", 0, 12),
            row("week", "درس هذا الأسبوع", 5, 4),
            row("boundary", "تمام سبعة أيام", 7, 20),
            row("month", "درس هذا الشهر", 15, 60),
            row("future", "تاريخ مستقبلي", -1, 33),
        )
        val analytics = fetchListeningAnalytics(AdminWorkspaceApi {
            paths += it
            when (it) {
                ListeningLessonPath -> fixture
                ListeningSectionPath -> items(section("s", "أصول"))
                else -> error("Unexpected endpoint")
            }
        }, now)
        val all = analytics.byAddedRange.getValue(LessonAddedRange.ALL)
        val week = analytics.byAddedRange.getValue(LessonAddedRange.WEEK)
        val month = analytics.byAddedRange.getValue(LessonAddedRange.MONTH)
        assertEquals(listOf("/admin/lessons", "/admin/subcategories"), paths)
        assertEquals(529L, all.cumulativeCount)
        assertEquals(16L, week.cumulativeCount)
        assertEquals(96L, month.cumulativeCount)
        assertEquals(2, week.lessons)
        assertEquals(4, month.lessons)
        assertEquals(1, week.recentAgeUnknown)
        assertEquals(listOf("درس حديث", "درس هذا الأسبوع"), week.topLessons.map { it.label })
        assertEquals(ListeningRank("أصول", 16), week.topSections.single())
    }

    @Test
    fun sectionAndLessonRanksIgnoreDeletedAndPrivateIdentityFields() {
        val lessons = items(row("a", "نص public@example.org", 5, 9), row("b", "درس آخر", 5, 6),
            row("c", "محذوف", 5, 200, deleted = now), row("d", "بلا فرع", 5, 3, "gone"))
        val sections = items(section("s", "الفقه"), section("gone", "قسم محذوف", deleted = now))
        val rank = summarizeListening(lessons, sections, now).byAddedRange.getValue(LessonAddedRange.ALL)
        assertEquals(18, rank.cumulativeCount.toInt())
        assertEquals(listOf("نص …", "درس آخر", "بلا فرع"), rank.topLessons.map { it.label })
        assertEquals(listOf(ListeningRank("الفقه", 15)), rank.topSections)
        assertFalse(rank.toString().contains("@example.org"))
        assertFalse(rank.toString().contains("not-for-display"))
        assertFalse(rank.toString().contains("قسم محذوف"))
    }

    @Test
    fun duplicateOrMalformedCountsFailWithoutSyntheticZeros() {
        val subst = items(section("s", "فرع"))
        assertTrue(runCatching { summarizeListening(JSONObject(), subst, now) }.isFailure)
        assertTrue(runCatching { summarizeListening(items(row("a", "x", 0, -1)), subst, now) }.isFailure)
        assertTrue(runCatching { summarizeListening(items(row("a", "x", 0, 1.25)), subst, now) }.isFailure)
        assertTrue(runCatching { summarizeListening(items(row("a", "x", 0, 2).apply { remove("views") }), subst, now) }.isFailure)
        assertTrue(runCatching { summarizeListening(items(row("a", "x", 0, 2), row("a", "x", 0, 2)), subst, now) }.isFailure)
    }

    @Test
    fun genuineEmptyAnalyticsAndLargeValuesRemainUseful() {
        val empty = summarizeListening(items(), items(), now).byAddedRange.getValue(LessonAddedRange.ALL)
        assertEquals(0, empty.lessons)
        assertEquals(0L, empty.cumulativeCount)
        assertTrue(empty.topLessons.isEmpty())
        val many = summarizeListening(items(row("a", "x", 0, Int.MAX_VALUE), row("b", "y", 0, 800)), items(section("s", "فرع")), now)
            .byAddedRange.getValue(LessonAddedRange.ALL)
        assertEquals(Int.MAX_VALUE.toLong() + 800L, many.cumulativeCount)
    }
}
