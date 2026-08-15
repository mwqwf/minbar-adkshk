package com.ali.menbaradkshk.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

/**
 * 📋 نسخ أيّ نصّ في التطبيق — بالضغط المطوّل.
 *
 * **لماذا الضغط المطوّل لا زرّ نسخ بجانب كل نصّ؟** زرٌّ لكل عنوان يعني عشرات
 * الأيقونات المبعثرة في كل شاشة — ضجيج بصريّ يخالف قاعدة البساطة. والضغط
 * المطوّل هو الإيماءة التي يعرفها كل مستخدم هاتف أصلاً من الرسائل والمتصفّح،
 * فلا يحتاج تعلّماً. وتُدعَم بتأكيدين لا واحد: اهتزازة **و**رسالة قصيرة، كي
 * يفهم مَن لا يقرأ العربية أنّ شيئاً حدث.
 *
 * ملاحظة: من أندرويد 13 يعرض النظام نفسه شريط تأكيد للنسخ، فلا نُكرّره —
 * رسالتان متتاليتان لنفس الفعل تربكان.
 */
fun copyToClipboard(context: Context, text: String, label: String = "منبر ادكصهك") {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    runCatching { manager.setPrimaryClip(ClipData.newPlainText(label, trimmed)) }
        .onSuccess {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(context, "نُسخ النصّ", Toast.LENGTH_SHORT).show()
            }
        }
}

/**
 * يجعل أي عنصر قابلاً للنسخ بالضغط المطوّل، مع إبقاء نقرته العاديّة كما هي.
 *
 * @param text النصّ المنسوخ (يُبنى كسلًا: بعض النصوص تُركَّب من عدّة حقول).
 * @param onClick سلوك النقرة العاديّة إن كان للعنصر سلوك؛ وإلا فالنقرة لا تفعل شيئاً.
 */
fun Modifier.copyableOnLongPress(
    text: () -> String,
    onClick: (() -> Unit)? = null,
): Modifier = composed {
    val context = LocalContext.current
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    combinedClickable(
        interactionSource = interaction,
        indication = null,
        onLongClick = {
            runCatching {
                view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            }
            copyToClipboard(context, text())
        },
        onClick = { onClick?.invoke() },
    )
}

/** الصيغة الجاهزة لنصّ ثابت. */
@Composable
fun Modifier.copyable(text: String, onClick: (() -> Unit)? = null): Modifier =
    this.copyableOnLongPress(text = { text }, onClick = onClick)
