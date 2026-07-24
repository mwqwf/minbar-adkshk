package com.ali.menbaradkshk.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Mosque
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

// لون إبراز ثابت لكل قسم بحسب معرّفه (مطابق لـ category_colors.dart في الأصل).
// درجات عميقة منخفضة التشبّع تحقق تبايناً مناسباً مع النص الأبيض.
private val categoryPalette = listOf(
    Teal,
    BlueBrand,
    Gold,
    GreenBrand,
    OrangeBrand,
    Color(0xFF685487), // بنفسجي تراثي هادئ
    Color(0xFF884A52), // عنّابي دافئ
    Color(0xFF2F6E70), // فيروزي عميق
)

private val categoryIcons = listOf(
    Icons.Filled.MenuBook,
    Icons.Filled.Mosque,
    Icons.Filled.AutoStories,
    Icons.Filled.School,
    Icons.Filled.Lightbulb,
    Icons.Filled.Favorite,
    Icons.Filled.Star,
    Icons.Filled.Headphones,
)

private fun hashOf(id: String, size: Int): Int {
    if (id.isEmpty()) return 0
    var hash = 0
    for (ch in id) hash = (hash + ch.code) % size
    return hash
}

fun colorForCategory(id: String): Color {
    if (id.isEmpty()) return Teal
    return categoryPalette[hashOf(id, categoryPalette.size)]
}

fun iconForCategory(id: String): ImageVector {
    if (id.isEmpty()) return Icons.Filled.Folder
    return categoryIcons[hashOf(id, categoryIcons.size)]
}
