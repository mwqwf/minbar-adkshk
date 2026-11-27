package com.ali.menbaradkshk.adminsurface

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

internal enum class SectionNameKind(val routeName: String) {
    Category("categories"), Subcategory("subcategories"),
}
internal data class SectionNameTarget(val kind: SectionNameKind, val id: String)
internal fun interface SectionRenameTransport {
    suspend fun request(method: String, path: String, body: JSONObject?): JSONObject
}
internal data class SectionNameRevision(
    val target: SectionNameTarget,
    val name: String,
    val updatedAtMs: Long,
    val categoryId: String?,
)

private val plainId = Regex("[A-Za-z0-9_.-]{1,128}")
private val sectionNamePath = Regex("/admin/guarded-section-names/(?:categories|subcategories)/([A-Za-z0-9_.-]{1,128})")
internal fun isSectionRenamePath(path: String): Boolean =
    sectionNamePath.matchEntire(path)?.groupValues?.get(1)?.let { it != "." && it != ".." } == true

internal fun sectionNameTarget(kind: SectionNameKind, data: JSONObject): SectionNameTarget? =
    (data.opt("id") as? String)?.takeIf { it.matches(plainId) && it != "." && it != ".." }
        ?.let { SectionNameTarget(kind, it) }

private fun namePath(t: SectionNameTarget): String {
    require(t.id.matches(plainId) && t.id != "." && t.id != "..")
    return "/admin/guarded-section-names/${t.kind.routeName}/${t.id}"
}

private fun revision(data: JSONObject, target: SectionNameTarget): SectionNameRevision {
    if (data.keys().asSequence().toSet() != setOf("id","name","updatedAtMs","categoryId") ||
        data.opt("id") != target.id) throw IOException("Unexpected section")
    val name = (data.opt("name") as? String)?.takeIf { it.length <= 512 } ?: throw IOException("Invalid section")
    val updated = when (val time = data.opt("updatedAtMs")) {
        is Int -> time.toLong()
        is Long -> time
        else -> throw IOException("Invalid revision")
    }
    if (updated !in 0 until 9_007_199_254_740_991L) throw IOException("Invalid revision")
    val parent = data.opt("categoryId")
    val categoryId = when (target.kind) {
        SectionNameKind.Category -> {
            if (parent !== JSONObject.NULL) throw IOException("Invalid section parent")
            null
        }
        SectionNameKind.Subcategory -> (parent as? String)?.takeIf { it.length in 1..128 }
            ?: throw IOException("Invalid section parent")
    }
    return SectionNameRevision(target, name, updated, categoryId)
}

internal fun canOfferSectionRename(role: AdminWorkspaceRole, whoAmI: JSONObject, buildEnabled: Boolean): Boolean =
    buildEnabled && role == AdminWorkspaceRole.OWNER && whoAmI.opt("canRenameSections") == true

internal class SectionRenameClient(private val wire: SectionRenameTransport, private val exactOwner: () -> Boolean) {
    private fun guard() {
        if (!exactOwner()) throw AdminAccessRejected()
    }

    suspend fun read(target: SectionNameTarget): SectionNameRevision {
        guard()
        val row = revision(wire.request("GET", namePath(target), null), target)
        guard()
        return row
    }

    suspend fun save(expected: SectionNameRevision, name: String): SectionNameRevision {
        guard()
        require(name.isNotEmpty() && name.length <= 160 && name.trim() == name && name != expected.name &&
            name.none { it.category == CharCategory.CONTROL || it.category == CharCategory.FORMAT })
        val body = JSONObject().put("expectedName", expected.name).put("expectedUpdatedAtMs", expected.updatedAtMs)
            .put("expectedCategoryId", expected.categoryId ?: JSONObject.NULL).put("name", name)
        val row = revision(wire.request("PATCH", namePath(expected.target), body), expected.target)
        guard()
        if (row.name != name || row.categoryId != expected.categoryId || row.updatedAtMs <= expected.updatedAtMs)
            throw IOException("Rename receipt not verified")
        return row
    }
}

/** Caller must bind to the verified owner, literal permission and current in-memory session key.
 * No network request occurs during composition. The server independently fences every mutation. */
@Composable
internal fun AdminSectionRenamePanel(
    target: SectionNameTarget,
    activeOwnerCapability: Boolean,
    sessionEpoch: Any,
    client: SectionRenameClient,
    onSaved: (SectionNameRevision) -> Unit,
) {
    if (!activeOwnerCapability || !target.id.matches(plainId)) return
    val coroutine = rememberCoroutineScope()
    var current by remember(sessionEpoch,target) { mutableStateOf<SectionNameRevision?>(null) }
    var entry by remember(sessionEpoch,target) { mutableStateOf("") }
    var busy by remember(sessionEpoch,target) { mutableStateOf(false) }
    var confirm by remember(sessionEpoch,target) { mutableStateOf(false) }
    var info by remember(sessionEpoch,target) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = !busy, onClick = {
            busy = true
            info = null
            current = null
            coroutine.launch {
                try {
                    val row = client.read(target)
                    entry = row.name
                    current = row
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { info = "تعذّر قراءة اسم القسم." }
                finally { busy = false }
            }
        }) { Text("اقرأ الاسم الحالي") }
        val row = current
        if (row != null) {
            OutlinedTextField(value = entry, onValueChange = { if (it.length <= 160) entry = it },
                enabled = !busy, singleLine = true, label = { Text("الاسم الجديد") })
            val newName = entry.trim()
            Button(enabled = !busy && newName.isNotEmpty() && newName != row.name,
                onClick = { confirm = true }) { Text("راجع تغيير الاسم") }
            if (confirm) AlertDialog(
                onDismissRequest = { confirm = false },
                title = { Text("تأكيد تغيير اسم القسم") },
                text = { Text("من «${row.name}» إلى «$newName». لا يتغيّر ترتيب القسم أو محتواه.") },
                confirmButton = {
                    TextButton(enabled = !busy, onClick = {
                        confirm = false
                        busy = true
                        info = null
                        coroutine.launch {
                            try {
                                val saved = client.save(row,newName)
                                current = saved
                                entry = saved.name
                                info = "حُفظ اسم القسم."
                                onSaved(saved)
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) {
                                current = null
                                info = "لم يُؤكَّد الحفظ. اقرأ الاسم الحالي قبل محاولة أخرى."
                            } finally { busy = false }
                        }
                    }) { Text("أكّد الحفظ") }
                },
                dismissButton = { TextButton(onClick = { confirm = false }) { Text("رجوع") } },
            )
        }
        info?.let { Text(it) }
    }
}
