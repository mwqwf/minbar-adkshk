package com.ali.menbaradkshk.adminsurface

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ali.menbaradkshk.ui.adminbridge.AdminBridgeFailure
import com.ali.menbaradkshk.ui.adminbridge.order.GuardedLessonOrderClient
import com.ali.menbaradkshk.ui.adminbridge.order.GuardedOrderDirection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun AdminLessonOrderDialog(
    original: LessonMetadata, client: GuardedLessonOrderClient,
    onBack: () -> Unit, onRejected: () -> Unit, result: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var frame by remember(client,original) { mutableStateOf<GuardedLessonOrderClient.Review?>(null) }
    var direction by remember(client,original) { mutableStateOf<GuardedOrderDirection?>(null) }
    var loadingFailed by remember(client,original) { mutableStateOf(false) }
    var busy by remember(client,original) { mutableStateOf(false) }
    DisposableEffect(client,original) { onDispose { client.discard() } }
    LaunchedEffect(client,original) {
        try {
            frame = client.review(original.categoryId,original.subcategoryId,original.id)
        } catch (c: CancellationException) {
            throw c
        } catch (e: AdminBridgeFailure) {
            if (e.statusCode == 401 || e.statusCode == 403) onRejected() else loadingFailed = true
        } catch (_: Exception) { loadingFailed = true }
    }

    fun submit(item: GuardedLessonOrderClient.Review, move: GuardedOrderDirection) {
        if (busy || frame !== item || direction != move || !client.available()) return
        busy = true
        scope.launch {
            try {
                val newPosition = client.confirm(item,move)
                result("حُفظ الموضع $newPosition داخل الفرع. لم يتغيّر تاريخ الدرس.")
            } catch (c: CancellationException) {
                throw c
            } catch (e: AdminBridgeFailure) {
                when (e.statusCode) {
                    401, 403 -> onRejected()
                    404, 409 -> result("تغيّرت القائمة أو لم تعد متاحة؛ أعد فتح الدرس قبل المحاولة.")
                    else -> result("لم تُؤكّد النتيجة. راجع القائمة قبل المحاولة من جديد.")
                }
            } catch (_: Exception) {
                result("لم تُؤكّد النتيجة. راجع القائمة قبل المحاولة من جديد.")
            } finally { busy = false }
        }
    }

    val item = frame
    val move = direction
    AlertDialog(
        onDismissRequest = { if (!busy) onBack() },
        title = { Text("ترتيب الدرس في هذا الفرع") },
        text = {
            Column(
                Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(item?.target?.title ?: original.title, style = MaterialTheme.typography.titleMedium)
                when {
                    busy -> Text("جارٍ التحقّق من ترتيب الفرع وحفظ الخطوة…")
                    loadingFailed -> Text("تعذّر تحميل قائمة مكتملة قابلة للترتيب.")
                    item == null -> Text("تُحمَّل قائمة الدروس المنشورة…")
                    move == GuardedOrderDirection.Up -> Text(
                        "سيصبح الدرس قبل «${item.before?.title.orEmpty()}» داخل الفرع فقط.")
                    move == GuardedOrderDirection.Down -> Text(
                        "سيصبح الدرس بعد «${item.after?.title.orEmpty()}» داخل الفرع فقط.")
                    else -> {
                        item.before?.let { neighbor ->
                            TextButton(onClick = { direction = GuardedOrderDirection.Up }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text("قدِّمه على «${neighbor.title}»")
                            }
                        }
                        item.after?.let { neighbor ->
                            TextButton(onClick = { direction = GuardedOrderDirection.Down }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text("أخِّره بعد «${neighbor.title}»")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (item != null && move != null) {
                Button(onClick = { submit(item,move) }, enabled = !busy && client.available(),
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("أؤكّد تغيير الموضع") }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (move == null) onBack() else direction = null }, enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("رجوع") }
        },
    )
}
