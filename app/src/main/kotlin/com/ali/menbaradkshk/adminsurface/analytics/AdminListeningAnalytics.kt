package com.ali.menbaradkshk.adminsurface.analytics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ali.menbaradkshk.adminsurface.AdminAccessRejected
import com.ali.menbaradkshk.adminsurface.AdminWorkspaceApi
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface AnalyticsState {
    data object Loading : AnalyticsState
    data object Failed : AnalyticsState
    data object Rejected : AnalyticsState
    data class Ready(val analytics: ListeningAnalytics) : AnalyticsState
}

/** Host must mount this inside an already validated admin session; nothing is retained on disk. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AdminListeningAnalytics(api: AdminWorkspaceApi, onRejected: () -> Unit) {
    val currentApi by rememberUpdatedState(api)
    val rejection by rememberUpdatedState(onRejected)
    var attempt by remember { mutableIntStateOf(0) }
    var range by remember { mutableStateOf(LessonAddedRange.ALL) }
    var state by remember(attempt) { mutableStateOf<AnalyticsState>(AnalyticsState.Loading) }
    LaunchedEffect(attempt) {
        try {
            val now = System.currentTimeMillis()
            state = AnalyticsState.Ready(withContext(Dispatchers.IO) { fetchListeningAnalytics(currentApi, now) })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: AdminAccessRejected) {
            state = AnalyticsState.Rejected
            rejection()
        } catch (_: Exception) {
            state = AnalyticsState.Failed
        }
    }
    val locale = remember { Locale.forLanguageTag("ar") }
    val number = remember { NumberFormat.getIntegerInstance(locale) }
    val date = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale) }
    when (val result = state) {
        AnalyticsState.Loading -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator()
            Text("جارٍ تحميل عدّادات الاستماع…", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        AnalyticsState.Failed -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("تعذّر إحضار العدّادات. لم تُستبدل البيانات بأصفار.")
            Button(onClick = { attempt++ }, modifier = Modifier.heightIn(min = 48.dp)) { Text("إعادة المحاولة") }
        }
        AnalyticsState.Rejected -> Text("تعذّر التحقّق من صلاحية المشاهدة.", Modifier.padding(16.dp))
        is AnalyticsState.Ready -> {
            val cohort = result.analytics.byAddedRange.getValue(range)
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text("الدروس بحسب تاريخ إضافتها", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LessonAddedRange.entries.forEach { window ->
                            FilterChip(selected = range == window, onClick = { range = window },
                                label = { Text(window.label) }, modifier = Modifier.heightIn(min = 48.dp))
                        }
                    }
                    Text(
                        "العداد تراكمي لكل درس؛ اختيار الفترة يرشّح تاريخ إضافة الدرس، لا تاريخ الاستماع. الأرقام ليست عدد المستمعين.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (cohort.recentAgeUnknown > 0) Text(
                        "استُبعد ${number.format(cohort.recentAgeUnknown)} من الدروس التي لا يُعرف تاريخ إضافتها.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${number.format(cohort.cumulativeCount)} في عدّادات الاستماع", style = MaterialTheme.typography.titleLarge)
                            Text("في ${number.format(cohort.lessons)} من الدروس المختارة", style = MaterialTheme.typography.bodyMedium)
                            Text("وقت القراءة: ${date.format(Date(result.analytics.measuredAtMs))}", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { attempt++ }, modifier = Modifier.heightIn(min = 48.dp)) { Text("تحديث العدّادات") }
                        }
                    }
                }
                rankings("أعلى الدروس في العدّاد", cohort.topLessons, number)
                rankings("أعلى الأقسام الفرعية الحيّة", cohort.topSections, number)
            }
        }
    }
}

private fun LazyListScope.rankings(title: String, ranks: List<ListeningRank>, number: NumberFormat) {
    item { Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
    if (ranks.isEmpty()) item { Text("لا توجد نتائج موجبة لهذا الاختيار.") }
    else itemsIndexed(ranks) { index, rank ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("${number.format(index + 1)}. ${rank.label}", style = MaterialTheme.typography.bodyLarge)
                Text("العداد: ${number.format(rank.count)}", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
