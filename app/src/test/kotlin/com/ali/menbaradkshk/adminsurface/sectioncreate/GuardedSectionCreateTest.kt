package com.ali.menbaradkshk.adminsurface.sectioncreate

import android.app.Application
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
class GuardedSectionCreateTest {
    private val op = "cd790800-0000-4000-8000-000000000001"
    private val example = SectionCreateParent("cat_main", "أبواب الفقه", 99)
    private fun rawParent() = JSONObject().put("id", example.id).put("name", example.name).put("updatedAtMs", 99)
    private fun rawParents() = JSONObject().put("items", JSONArray().put(rawParent())).put("truncated", false)
    private fun rawReceipt(intent: SectionCreateIntent) = JSONObject()
        .put("operationId", intent.operationId).put("name", intent.name)
        .put("id", (if (intent.parent == null) "cat_" else "sub_") + intent.operationId.replace("-", ""))
        .put("categoryId", intent.parent?.id ?: JSONObject.NULL).put("updatedAtMs", 100)

    @Test fun strictNameParentAndReceiptProfiles() {
        assertEquals("أبواب", normalizedSectionCreationName("  ا\u0654بواب "))
        assertNull(normalizedSectionCreationName("اسم\u202Eخفي"))
        assertNull(normalizedSectionCreationName("اسم\u0000خفي"))
        assertEquals(example, decodeSectionCreateParents(rawParents()).items.single())
        val selected = SectionCreateIntent(op, "الطهارة", example)
        assertEquals(example.id, decodeSectionCreateReceipt(rawReceipt(selected), selected).categoryId)
        for ((k,v) in listOf("categoryId" to "other", "updatedAtMs" to 99, "updatedAtMs" to "100",
            "id" to "wrong", "operationId" to "wrong", "name" to "different")) {
            assertTrue(runCatching { decodeSectionCreateReceipt(rawReceipt(selected).put(k,v), selected) }.isFailure)
        }
        assertTrue(runCatching { decodeSectionCreateParents(rawParents().put("truncated","false")) }.isFailure)
    }

    @Test fun freezesExactIntentAndRejectsUnsafeNamesBeforeWire() = runBlocking {
        val args = mutableListOf<String>()
        val intent = SectionCreateIntent(op, "الطهارة", example)
        val access = GuardedSectionCreateAccess({ true }) { method, path, body ->
            if (method == "GET") {
                assertEquals(SECTION_CREATE_PARENT_PATH,path)
                assertNull(body)
                rawParents()
            } else {
                assertEquals("POST",method)
                assertEquals(SECTION_CREATE_PATH,path)
                assertEquals(example.id, body?.getJSONObject("parent")?.getString("id"))
                args += body.toString()
                rawReceipt(intent)
            }
        }
        assertEquals(example, access.parents().items.single())
        assertTrue(runCatching { access.create(intent.copy(operationId="id-from-caller")) }.isFailure)
        assertTrue(runCatching { access.create(intent.copy(name=" غير مشذّب ")) }.isFailure)
        access.create(intent)
        access.create(intent)
        assertEquals(2,args.size)
        assertEquals(args[0],args[1])
    }

    @Test fun closedLeaseDropsInFlightData() = runBlocking {
        var allowed = true
        val intent = SectionCreateIntent(op,"الأبواب",null)
        var calls = 0
        val access = GuardedSectionCreateAccess({ allowed }) { _,_,_ ->
            allowed = false
            calls++
            rawReceipt(intent)
        }
        assertTrue(runCatching { access.create(intent) }.isFailure)
        assertFalse(access.available())
        assertTrue(runCatching { access.create(intent) }.isFailure)
        assertEquals(1,calls)
    }
}
