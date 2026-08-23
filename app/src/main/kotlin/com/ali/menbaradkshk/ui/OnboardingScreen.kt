package com.ali.menbaradkshk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ali.menbaradkshk.data.ContentState

/**
 * 👋 أوّل تشغيل — سؤالان اثنان، ثم لا شيء بعدهما أبداً.
 *
 * **لماذا وُجدت هذه الشاشة؟** أقوى ما في التطبيق (حفظ الدروس تلقائياً على
 * الواي فاي لتُسمَع بلا إنترنت) كان مدفوناً في الإعدادات، وأغلب الجمهور لا
 * يفتح الإعدادات قطّ. فالميزة التي تحلّ مشكلة الإنترنت المتقطّع لا تصل إلى
 * من صُنعت لأجله.
 *
 * **ولماذا سؤالان فقط؟** لأنّ كل سؤال ثالث يعني من يغلق التطبيق قبل أن يبدأ.
 * سؤالٌ واحد كبير في كل شاشة، بخطّ كبير وأزرار عريضة، و«تخطَّ» ظاهرٌ في
 * كلتيهما — لا حبس بأيّ حال.
 *
 * وتُعرض **بعد** وصول المحتوى الأوّل: بلا محتوى تكون قائمة الأقسام فارغة
 * فيبدو التطبيق معطوباً في أوّل لحظة يُرى فيها. وإن لم يكن هناك اتصال
 * تُؤجَّل إلى أوّل مرّة يتوفّر فيها المحتوى.
 */
@Composable
fun OnboardingScreen(vm: AppViewModel, content: ContentState, onDone: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    // اسم القسم المختار — يُذكر في السؤال الثاني كي يبقى مرتبطاً بجواب الأوّل.
    var chosenName by rememberSaveable { mutableStateOf("") }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        if (step == 0) {
            SectionQuestion(
                vm = vm,
                content = content,
                onChosen = { name ->
                    chosenName = name
                    step = 1
                },
                onSkip = { step = 1 },
            )
        } else {
            AutoDownloadQuestion(
                vm = vm,
                sectionName = chosenName,
                onDone = onDone,
            )
        }
    }
}

/// السؤال الأوّل: أيّ قسم تتابع؟ اختيار القسم = متابعته (نفس جرس المتابعة
/// الموجود في شاشة الأقسام) — فلا آليّة ثانية ولا معنى جديد يتعلّمه المستخدم.
@Composable
private fun SectionQuestion(
    vm: AppViewModel,
    content: ContentState,
    onChosen: (String) -> Unit,
    onSkip: () -> Unit,
) {
    val categoryNames = content.categories.associate { it.id to it.name }
    Column(Modifier.fillMaxSize()) {
        QuestionTitle("ما القسم الذي تتابعه؟")
        Text(
            "اختر واحداً، ويصلك كل درس جديد فيه.",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(content.subcategories, key = { it.id }) { sub ->
                SectionCard(
                    categoryId = sub.categoryId,
                    name = sub.name,
                    subtitle = categoryNames[sub.categoryId],
                    onClick = {
                        if (!vm.store.isFollowingSubcategory(sub.id)) vm.toggleFollow(sub.id)
                        // التحميل التلقائي — إن فُعِّل في السؤال التالي —
                        // يتبع «الأقسام التي أتابعها»، وهو الهدف الموجود أصلاً
                        // في الإعدادات بلا صيغة جديدة.
                        vm.setAutoDownloadTarget("followed")
                        onChosen(sub.name)
                    },
                )
            }
        }
        SkipButton(onSkip)
    }
}

/// السؤال الثاني: أنُنزّل تلقائياً على الواي فاي؟ — الجواب زرّان لا أكثر.
@Composable
private fun AutoDownloadQuestion(
    vm: AppViewModel,
    sectionName: String,
    onDone: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        QuestionTitle(
            if (sectionName.isBlank()) {
                "هل ننزّل أحدث الدروس تلقائياً حين تتصل بواي فاي، لتسمعها بلا إنترنت؟"
            } else {
                "هل ننزّل دروس «$sectionName» تلقائياً حين تتصل بواي فاي، لتسمعها بلا إنترنت؟"
            },
        )
        Text(
            "لن نستعمل بيانات هاتفك، ويمكنك تغيير هذا من الإعدادات متى شئت.",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Button(
                onClick = {
                    // الواي فاي وحده: هذا نصّ السؤال حرفياً، فلا يجوز أن
                    // ينتهي الجواب بتحميلٍ على بيانات الهاتف.
                    if (sectionName.isBlank()) vm.setAutoDownloadTarget("recent")
                    vm.setAutoDownloadWifiOnly(true)
                    vm.setAutoDownloadEnabled(true)
                    onDone()
                },
                modifier = Modifier.fillMaxWidth().height(68.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("نعم", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = {
                    vm.setAutoDownloadEnabled(false)
                    onDone()
                },
                modifier = Modifier.fillMaxWidth().height(68.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("لا", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
        }
        SkipButton(onDone)
    }
}

@Composable
private fun QuestionTitle(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 28.dp, bottom = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/// «تخطَّ» ظاهرٌ في كل شاشة — لا حبس ولا سؤال إجباريّ.
@Composable
private fun SkipButton(onSkip: () -> Unit) {
    TextButton(
        onClick = onSkip,
        modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 22.dp),
    ) {
        Text("تخطَّ", style = MaterialTheme.typography.titleLarge)
    }
}
