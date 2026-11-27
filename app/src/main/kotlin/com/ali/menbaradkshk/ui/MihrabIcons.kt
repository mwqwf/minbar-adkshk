package com.ali.menbaradkshk.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * أيقونة «الرئيسية» في هوية «محراب الفجر»: محرابٌ مدبّب فيه قوسٌ داخليّ.
 * خطّية (غير محدّدة) ومملوءة (محدّدة)، على شبكة 24 كأيقونات Material.
 */
internal object MihrabIcons {
    private const val OUTER = "M5 21V11c0-4 3.2-6.6 7-8 3.8 1.4 7 4 7 8v10"
    private const val INNER = "M9 21v-7c0-1.8 1.3-3 3-3.6 1.7 .6 3 1.8 3 3.6v7"
    private const val BASE = "M3 21h18"

    val Outlined: ImageVector by lazy {
        ImageVector.Builder("MihrabOutlined", 24.dp, 24.dp, 24f, 24f).apply {
            listOf(OUTER, INNER, BASE).forEach { d ->
                addPath(
                    PathParser().parsePathString(d).toNodes(),
                    stroke = SolidColor(Color.Black), strokeLineWidth = 1.9f,
                    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
    }

    val Filled: ImageVector by lazy {
        ImageVector.Builder("MihrabFilled", 24.dp, 24.dp, 24f, 24f).apply {
            // المحراب مملوء، والقوس الداخليّ مفرّغ (evenOdd).
            addPath(
                PathParser().parsePathString("$OUTER H5z M9 21v-7c0-1.8 1.3-3 3-3.6 1.7 .6 3 1.8 3 3.6v7z").toNodes(),
                fill = SolidColor(Color.Black),
                pathFillType = androidx.compose.ui.graphics.PathFillType.EvenOdd,
            )
            addPath(
                PathParser().parsePathString(BASE).toNodes(),
                stroke = SolidColor(Color.Black), strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round,
            )
        }.build()
    }
}
