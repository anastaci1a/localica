package io.github.anastaci1a.localica.icons

import android.content.Context
import android.graphics.Color
import androidx.core.content.ContextCompat
import io.github.anastaci1a.localica.ui.AppIcon
import org.xmlpull.v1.XmlPullParser

/** Exports the app's original path geometry, without rendering a bitmap. */
internal fun builtinSvg(context: Context, icon: AppIcon): ByteArray {
    val ns = "http://schemas.android.com/apk/res/android"
    val out = StringBuilder()
    context.resources.getXml(icon.builtin).use { xml ->
        fun value(name: String, default: String) = xml.getAttributeValue(ns, name) ?: default
        fun float(name: String, default: Float) = xml.getAttributeFloatValue(ns, name, default)
        fun color(name: String): Int {
            val resource = xml.getAttributeResourceValue(ns, name, 0)
            return if (resource != 0) ContextCompat.getColor(context, resource)
            else xml.getAttributeIntValue(ns, name, Color.TRANSPARENT)
        }
        fun escape(text: String) = text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
        while (xml.eventType != XmlPullParser.END_DOCUMENT) {
            if (xml.eventType == XmlPullParser.START_TAG) when (xml.name) {
                "vector" -> out.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ${float("viewportWidth", 24f)} ${float("viewportHeight", 24f)}\">")
                "group" -> {
                    val px = float("pivotX", 0f); val py = float("pivotY", 0f)
                    out.append("<g transform=\"translate(${float("translateX", 0f) + px} ${float("translateY", 0f) + py}) rotate(${float("rotation", 0f)}) scale(${float("scaleX", 1f)} ${float("scaleY", 1f)}) translate(${-px} ${-py})\">")
                }
                "path" -> {
                    val fill = color("fillColor"); val stroke = color("strokeColor")
                    out.append("<path d=\"${escape(value("pathData", ""))}\" fill=\"${if (Color.alpha(fill) == 0) "none" else "black"}\" stroke=\"${if (Color.alpha(stroke) == 0) "none" else "black"}\" ")
                    out.append("fill-opacity=\"${Color.alpha(fill) / 255f * float("fillAlpha", 1f)}\" stroke-opacity=\"${Color.alpha(stroke) / 255f * float("strokeAlpha", 1f)}\" ")
                    val cap = when (xml.getAttributeIntValue(ns, "strokeLineCap", 0)) { 1 -> "round"; 2 -> "square"; else -> "butt" }
                    val join = when (xml.getAttributeIntValue(ns, "strokeLineJoin", 0)) { 1 -> "round"; 2 -> "bevel"; else -> "miter" }
                    val rule = if (xml.getAttributeIntValue(ns, "fillType", 0) == 1) "evenodd" else "nonzero"
                    out.append("stroke-width=\"${float("strokeWidth", 0f)}\" stroke-linecap=\"$cap\" stroke-linejoin=\"$join\" stroke-miterlimit=\"${float("strokeMiterLimit", 4f)}\" fill-rule=\"$rule\"/>")
                }
                else -> error("Unsupported built-in vector element: ${xml.name}")
            }
            if (xml.eventType == XmlPullParser.END_TAG) when (xml.name) {
                "vector" -> out.append("</svg>")
                "group" -> out.append("</g>")
            }
            xml.next()
        }
    }
    return out.toString().toByteArray()
}
