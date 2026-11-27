package com.ali.menbaradkshk.adminsurface

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], manifest = Config.NONE)
class AdminSectionRenameTest {
    private val category = SectionNameTarget(SectionNameKind.Category, "cat")
    private fun row(name: String = "قديم", revision: Long = 4) =
        JSONObject().put("id", "cat").put("name", name).put("updatedAtMs", revision)
            .put("categoryId", JSONObject.NULL)

    @Test fun capabilityIsExactAndDefaultOff() {
        for (invalid in listOf(JSONObject(), JSONObject().put("canRenameSections", "true"))) {
            assertFalse(canOfferSectionRename(AdminWorkspaceRole.OWNER,invalid,true))
        }
        val good = JSONObject().put("canRenameSections", true)
        assertFalse(canOfferSectionRename(AdminWorkspaceRole.SUPERVISOR,good,true))
        assertFalse(canOfferSectionRename(AdminWorkspaceRole.OWNER,good,false))
        assertTrue(canOfferSectionRename(AdminWorkspaceRole.OWNER,good,true))
    }

    @Test fun exactPrefetchedNameCasThenReceiptAndRevocation() = runBlocking {
        var live = true
        val requests = mutableListOf<String>()
        val client = SectionRenameClient(SectionRenameTransport { method,path,body ->
            requests += "$method $path"
            if (method == "GET") {
                assertNull(body)
                row()
            } else {
                assertEquals(setOf("expectedName","expectedUpdatedAtMs","expectedCategoryId","name"),
                    requireNotNull(body).keys().asSequence().toSet())
                assertEquals("قديم",body.getString("expectedName"))
                assertEquals(4L,body.getLong("expectedUpdatedAtMs"))
                assertTrue(body.isNull("expectedCategoryId"))
                row("جديد",5)
            }
        }, { live })
        val loaded = client.read(category)
        assertEquals("جديد",client.save(loaded,"جديد").name)
        live = false
        assertTrue(runCatching { client.save(loaded,"آخر") }.exceptionOrNull() is AdminAccessRejected)
        assertEquals(listOf("GET /admin/guarded-section-names/categories/cat","PATCH /admin/guarded-section-names/categories/cat"),requests)
    }

    @Test fun noTransportForMalformedIdOrNameAndRejectMalformedReceipt() = runBlocking {
        var called = 0
        val client = SectionRenameClient(SectionRenameTransport { _,_,_ ->
            called++
            row().put("updatedAtMs", "4")
        }, { true })
        assertTrue(runCatching { client.read(category.copy(id = "../cat")) }.isFailure)
        assertTrue(runCatching { client.save(SectionNameRevision(category,"قديم",4,null),"x\u202e") }.isFailure)
        assertEquals(0,called)
        assertTrue(runCatching { client.read(category) }.isFailure)
        assertEquals(1,called)
    }

    @Test fun actionableCategoryAndChildOnlyForScopedOwnerWithoutAdditionalFetch() = runBlocking {
        val paths=mutableListOf<String>()
        val wire=AdminWorkspaceApi { path ->
            paths+=path
            val rows=JSONArray()
            if(path == "/admin/categories") rows.put(JSONObject().put("id","cat").put("name","قسم"))
            else {
                rows.put(JSONObject().put("id","child").put("category_id","cat").put("name","فرع"))
                rows.put(JSONObject().put("id","stray").put("category_id","missing").put("name","منفصل"))
            }
            JSONObject().put("items",rows)
        }
        assertEquals(listOf(category,SectionNameTarget(SectionNameKind.Subcategory,"child")),
            loadRows(Page.SECTIONS,AdminWorkspaceRole.OWNER,wire,true).map { it.sectionTarget })
        assertEquals(listOf("/admin/categories","/admin/subcategories"),paths)
        assertEquals(listOf(null),loadRows(Page.SECTIONS,AdminWorkspaceRole.SUPERVISOR,wire,true).map { it.sectionTarget })
        assertEquals(listOf(null),loadRows(Page.SECTIONS,AdminWorkspaceRole.OWNER,wire,false).map { it.sectionTarget })
        assertFalse(isSectionRenamePath("/admin/guarded-section-names/categories/.."))
        assertFalse(isSectionRenamePath("/admin/guarded-section-names/categories/cat?all=1"))
        assertTrue(isSectionRenamePath("/admin/guarded-section-names/subcategories/child"))
    }
}
