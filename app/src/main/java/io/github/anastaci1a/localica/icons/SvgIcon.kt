package io.github.anastaci1a.localica.icons

import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

internal data class SvgIcon(val viewBox: List<Float>, val children: List<SvgNode>)
internal sealed interface SvgNode {
    data class Group(val transforms: List<SvgTransform>, val children: List<SvgNode>) : SvgNode
    data class Path(val data: String, val style: SvgStyle) : SvgNode
}
internal data class SvgTransform(val name: String, val values: List<Float>)
internal data class SvgStyle(val fill: Boolean = true, val stroke: Boolean = false,
    val width: Float = 1f, val cap: String = "butt", val join: String = "miter",
    val rule: String = "nonzero", val miter: Float = 4f,
    val fillAlpha: Float = 1f, val strokeAlpha: Float = 1f)

/** No renderer, URI resolver, CSS engine or general SVG library runs during import. */
internal object SvgIconParser {
    const val MAX_BYTES = 256 * 1024
    private const val MAX_PATHS = 256
    private const val MAX_NODES = 1024
    private const val MAX_DEPTH = 16
    private const val MAX_NUMBERS = 16384
    private val number = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
    private val tokens = Regex("[MmZzLlHhVvCcSsQqTtAa]|${number.pattern}")
    private val styleKeys = setOf("fill", "stroke", "stroke-width", "stroke-linecap",
        "stroke-linejoin", "stroke-miterlimit", "fill-rule", "fill-opacity", "stroke-opacity")

    fun parse(bytes: ByteArray): SvgIcon {
        require(bytes.size <= MAX_BYTES) { "SVG exceeds 256 KB" }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        require(!Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            "SVG declarations and entities are forbidden"
        }
        val handler = Handler()
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
        // The lexical ban is independent of optional platform SAX feature support.
        listOf("http://xml.org/sax/features/external-general-entities",
            "http://xml.org/sax/features/external-parameter-entities",
            "http://apache.org/xml/features/nonvalidating/load-external-dtd").forEach {
            runCatching { factory.setFeature(it, false) }
        }
        val reader = factory.newSAXParser().xmlReader
        reader.contentHandler = handler
        reader.errorHandler = handler
        reader.entityResolver = org.xml.sax.EntityResolver { _, _ -> throw SAXException("External references are forbidden") }
        reader.parse(InputSource(StringReader(text)))
        return requireNotNull(handler.result) { "SVG root is missing" }
    }

    private fun scalar(value: String): Float {
        require(number.matches(value)) { "Invalid SVG number" }
        return value.toFloat().also { require(it.isFinite() && kotlin.math.abs(it) <= 100000f) { "SVG number is out of range" } }
    }

    private fun numbers(value: String): List<Float> {
        var end = 0
        val result = number.findAll(value).map { match ->
            require(value.substring(end, match.range.first).all { it.isWhitespace() || it == ',' }) { "Invalid SVG number list" }
            end = match.range.last + 1
            scalar(match.value)
        }.toList()
        require(value.substring(end).all { it.isWhitespace() || it == ',' }) { "Invalid SVG number list" }
        require(result.size <= MAX_NUMBERS) { "Too many SVG numbers" }
        return result
    }

    private fun paint(value: String): Boolean {
        require(value in setOf("none", "currentColor", "black", "white") ||
            value.matches(Regex("#[0-9a-fA-F]{3}|#[0-9a-fA-F]{6}"))) { "Unsupported SVG paint" }
        return value != "none"
    }

    private fun style(parent: SvgStyle, a: Map<String, String>): SvgStyle {
        fun choice(key: String, fallback: String, allowed: Set<String>) = a[key]?.also {
            require(it in allowed) { "Unsupported SVG $key" }
        } ?: fallback
        fun positive(key: String, fallback: Float) = a[key]?.let(::scalar)?.also {
            require(it >= 0f) { "Negative SVG $key" }
        } ?: fallback
        fun alpha(key: String, fallback: Float) = positive(key, fallback).also {
            require(it <= 1f) { "SVG opacity is out of range" }
        }
        return SvgStyle(a["fill"]?.let(::paint) ?: parent.fill, a["stroke"]?.let(::paint) ?: parent.stroke,
            positive("stroke-width", parent.width), choice("stroke-linecap", parent.cap, setOf("butt", "round", "square")),
            choice("stroke-linejoin", parent.join, setOf("miter", "round", "bevel")),
            choice("fill-rule", parent.rule, setOf("nonzero", "evenodd")),
            positive("stroke-miterlimit", parent.miter), alpha("fill-opacity", parent.fillAlpha), alpha("stroke-opacity", parent.strokeAlpha))
    }

    private fun transforms(value: String): List<SvgTransform> {
        val pattern = Regex("(translate|scale|rotate)\\s*\\(([^()]*)\\)")
        var end = 0
        val result = pattern.findAll(value).map { match ->
            require(value.substring(end, match.range.first).all { it.isWhitespace() || it == ',' }) { "Unsupported SVG transform" }
            end = match.range.last + 1
            val values = numbers(match.groupValues[2])
            val name = match.groupValues[1]
            require(if (name == "rotate") values.size in setOf(1, 3) else values.size in 1..2) { "Invalid SVG transform arguments" }
            SvgTransform(name, values)
        }.toList()
        require(value.substring(end).isBlank() && result.size <= 16) { "Unsupported SVG transform" }
        return result
    }

    private fun validatePath(data: String): Int {
        require(data.length <= 128 * 1024) { "SVG path is too long" }
        var end = 0
        var command = ' '
        var arguments = mutableListOf<Float>()
        var count = 0
        fun finish() {
            if (command == ' ') return
            val arity = when (command.lowercaseChar()) {
                'z' -> 0; 'h', 'v' -> 1; 'm', 'l', 't' -> 2; 's', 'q' -> 4; 'c' -> 6; 'a' -> 7
                else -> error("Unsupported SVG command")
            }
            require(if (arity == 0) arguments.isEmpty() else arguments.isNotEmpty() && arguments.size % arity == 0) { "Invalid SVG path arguments" }
            if (command.lowercaseChar() == 'a') arguments.chunked(7).forEach {
                require(it[0] >= 0f && it[1] >= 0f && it[3] in setOf(0f, 1f) && it[4] in setOf(0f, 1f)) { "Invalid SVG arc" }
            }
        }
        tokens.findAll(data).forEach { match ->
            require(++count <= MAX_NUMBERS) { "Too many SVG path tokens" }
            require(data.substring(end, match.range.first).all { it.isWhitespace() || it == ',' }) { "Invalid SVG path token" }
            end = match.range.last + 1
            val token = match.value
            if (token.length == 1 && token[0].isLetter()) {
                finish()
                if (command == ' ') require(token[0] == 'M' || token[0] == 'm') { "SVG path must begin with moveto" }
                command = token[0]; arguments = mutableListOf()
            } else {
                require(command != ' ') { "SVG path must begin with moveto" }
                arguments += scalar(token)
            }
        }
        require(data.substring(end).isBlank() && command != ' ') { "Invalid SVG path" }
        finish()
        return count
    }

    private fun combined(parent: List<Double>, transforms: List<SvgTransform>): List<Double> {
        var m = parent
        transforms.forEach { transform ->
            val v = transform.values.map { it.toDouble() }
            val t = when (transform.name) {
                "translate" -> listOf(1.0, 0.0, 0.0, 1.0, v[0], v.getOrElse(1) { 0.0 })
                "scale" -> listOf(v[0], 0.0, 0.0, v.getOrElse(1) { v[0] }, 0.0, 0.0)
                else -> {
                    val radians = Math.toRadians(v[0]); val c = kotlin.math.cos(radians); val s = kotlin.math.sin(radians)
                    val x = v.getOrElse(1) { 0.0 }; val y = v.getOrElse(2) { 0.0 }
                    listOf(c, s, -s, c, x - c * x + s * y, y - s * x - c * y)
                }
            }
            m = listOf(m[0]*t[0]+m[2]*t[1], m[1]*t[0]+m[3]*t[1],
                m[0]*t[2]+m[2]*t[3], m[1]*t[2]+m[3]*t[3],
                m[0]*t[4]+m[2]*t[5]+m[4], m[1]*t[4]+m[3]*t[5]+m[5])
            require(m.all { it.isFinite() && kotlin.math.abs(it) <= 1000000.0 }) { "SVG combined transform is out of range" }
        }
        return m
    }

    private class Frame(val tag: String, val style: SvgStyle, val transforms: List<SvgTransform> = emptyList(),
        val children: MutableList<SvgNode>? = mutableListOf(),
        val matrix: List<Double> = listOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0))
    private class Handler : DefaultHandler() {
        var result: SvgIcon? = null
        private var box: List<Float> = emptyList()
        private val stack = mutableListOf<Frame>()
        private var nodes = 0
        private var paths = 0
        private var pathNumbers = 0
        override fun processingInstruction(target: String?, data: String?) { error("SVG processing instructions are forbidden") }
        override fun characters(ch: CharArray, start: Int, length: Int) {
            require((start until start + length).all { ch[it].isWhitespace() }) { "SVG text is unsupported" }
        }
        override fun startElement(uri: String, local: String, qName: String, attributes: Attributes) {
            require(uri.isEmpty() || uri == "http://www.w3.org/2000/svg") { "Unsupported SVG namespace" }
            require(++nodes <= MAX_NODES && stack.size < MAX_DEPTH) { "SVG structure limit exceeded" }
            require(stack.isEmpty() || stack.last().children != null) { "SVG paths cannot contain elements" }
            val a = linkedMapOf<String, String>()
            for (i in 0 until attributes.length) {
                require(attributes.getURI(i).isEmpty()) { "Namespaced SVG attributes are unsupported" }
                a[attributes.getLocalName(i)] = attributes.getValue(i).trim()
            }
            val allowed = styleKeys + when (local) {
                "svg" -> setOf("viewBox", "width", "height", "version")
                "g" -> setOf("transform")
                "path" -> setOf("d")
                else -> error("Unsupported SVG element: $local")
            }
            require(a.keys.all { it in allowed }) { "Unsupported SVG attribute: ${a.keys.first { it !in allowed }}" }
            val s = style(stack.lastOrNull()?.style ?: SvgStyle(), a)
            when (local) {
                "svg" -> {
                    require(stack.isEmpty() && result == null) { "Nested SVG roots are unsupported" }
                    box = numbers(requireNotNull(a["viewBox"]) { "SVG viewBox is required" })
                    require(box.size == 4 && box[2] >= .01f && box[3] >= .01f) { "Invalid SVG viewBox" }
                    for (key in listOf("width", "height")) a[key]?.let { require(scalar(it.removeSuffix("px")) > 0f) { "Invalid SVG size" } }
                    stack += Frame(local, s)
                }
                "g" -> {
                    require(stack.isNotEmpty()) { "SVG root is required" }
                    val transforms = a["transform"]?.let(::transforms).orEmpty()
                    stack += Frame(local, s, transforms, matrix = combined(stack.last().matrix, transforms))
                }
                "path" -> {
                    require(stack.isNotEmpty() && ++paths <= MAX_PATHS) { "SVG path limit exceeded" }
                    val d = requireNotNull(a["d"]) { "SVG path d is required" }
                    pathNumbers += validatePath(d)
                    require(pathNumbers <= MAX_NUMBERS) { "SVG path complexity limit exceeded" }
                    stack.last().children!!.add(SvgNode.Path(d, s))
                    stack += Frame(local, s, children = null)
                }
            }
        }
        override fun endElement(uri: String, local: String, qName: String) {
            val frame = stack.removeAt(stack.lastIndex)
            if (local == "g") stack.last().children!!.add(SvgNode.Group(frame.transforms, frame.children!!.toList()))
            if (local == "svg") {
                require(paths > 0) { "SVG contains no paths" }
                result = SvgIcon(box, frame.children!!.toList())
            }
        }
    }
}
