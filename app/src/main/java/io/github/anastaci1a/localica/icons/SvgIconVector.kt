package io.github.anastaci1a.localica.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Imported paths use the same Compose vector tree and painter as resource vectors. */
internal fun SvgIcon.toImageVector(): ImageVector {
    val builder = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f)
    val scale = 24f / maxOf(viewBox[2], viewBox[3])
    builder.addGroup(scaleX = scale, scaleY = scale,
        translationX = (24f - viewBox[2] * scale) / 2f - viewBox[0] * scale,
        translationY = (24f - viewBox[3] * scale) / 2f - viewBox[1] * scale)
    fun append(nodes: List<SvgNode>) {
        nodes.forEach { node -> when (node) {
            is SvgNode.Path -> with(node.style) {
                builder.addPath(pathData = PathParser().parsePathString(node.data).toNodes(),
                    pathFillType = if (rule == "evenodd") PathFillType.EvenOdd else PathFillType.NonZero,
                    fill = if (fill) SolidColor(Color.Black) else null, fillAlpha = fillAlpha,
                    stroke = if (stroke) SolidColor(Color.Black) else null, strokeAlpha = strokeAlpha,
                    strokeLineWidth = width,
                    strokeLineCap = when (cap) { "round" -> StrokeCap.Round; "square" -> StrokeCap.Square; else -> StrokeCap.Butt },
                    strokeLineJoin = when (join) { "round" -> StrokeJoin.Round; "bevel" -> StrokeJoin.Bevel; else -> StrokeJoin.Miter },
                    strokeLineMiter = miter)
            }
            is SvgNode.Group -> {
                node.transforms.forEach { transform ->
                    val v = transform.values
                    when (transform.name) {
                        "translate" -> builder.addGroup(translationX = v[0], translationY = v.getOrElse(1) { 0f })
                        "scale" -> builder.addGroup(scaleX = v[0], scaleY = v.getOrElse(1) { v[0] })
                        "rotate" -> builder.addGroup(rotate = v[0], pivotX = v.getOrElse(1) { 0f }, pivotY = v.getOrElse(2) { 0f })
                    }
                }
                append(node.children)
                repeat(node.transforms.size) { builder.clearGroup() }
            }
        } }
    }
    append(children)
    builder.clearGroup()
    return builder.build()
}
