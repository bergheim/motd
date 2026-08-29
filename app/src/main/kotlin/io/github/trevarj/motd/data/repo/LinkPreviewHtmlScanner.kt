package io.github.trevarj.motd.data.repo

/** Small bounded HTML-head scanner. It never interprets body markup or executes page content. */
internal object LinkPreviewHtmlScanner {
    private const val MAX_TAG_CHARS = 16 * 1024
    private const val MAX_ATTRIBUTE_CHARS = 8 * 1024
    private const val MAX_ATTRIBUTES = 64
    private const val MAX_TITLE_CHARS = 8 * 1024
    private const val MAX_VALUES_PER_KEY = 8
    private val METADATA_KEYS =
        setOf(
            "og:title",
            "og:description",
            "og:image",
            "og:image:secure_url",
            "og:type",
            "og:video",
            "twitter:title",
            "twitter:description",
            "twitter:image",
            "twitter:image:src",
            "twitter:player",
            "twitter:card",
            "description",
        )

    data class Metadata(
        val values: Map<String, List<String>>,
        val title: String?,
        val charset: String?,
    )

    fun scan(html: String): Metadata {
        val values = LinkedHashMap<String, MutableList<String>>()
        var title: String? = null
        var charset: String? = null
        var position = 0
        while (position < html.length) {
            val start = html.indexOf('<', position)
            if (start < 0) break
            if (html.startsWith("<!--", start)) {
                val commentEnd = html.indexOf("-->", start + 4)
                if (commentEnd < 0) break
                position = commentEnd + 3
                continue
            }
            val end = tagEnd(html, start)
            if (end < 0) {
                position = minOf(html.length, start + MAX_TAG_CHARS)
                continue
            }
            var cursor = start + 1
            while (cursor < end && html[cursor].isWhitespace()) cursor++
            val closing = cursor < end && html[cursor] == '/'
            if (closing) cursor++
            val nameStart = cursor
            while (cursor < end && html[cursor].isLetterOrDigit()) cursor++
            val name = html.substring(nameStart, cursor).lowercase()
            if ((closing && name == "head") || (!closing && name == "body")) break

            if (!closing && name == "meta") {
                val attributes = attributes(html, cursor, end)
                charset = charset ?: attributes["charset"]?.take(MAX_ATTRIBUTE_CHARS)
                if (charset == null && attributes["http-equiv"].equals("content-type", ignoreCase = true)) {
                    val content = attributes["content"].orEmpty()
                    val marker = content.indexOf("charset=", ignoreCase = true)
                    if (marker >= 0) charset = content.substring(marker + 8).trim().trim('"', '\'', ' ')
                }
                val key = (attributes["property"] ?: attributes["name"])?.lowercase()
                val content = attributes["content"]
                if (key != null && key in METADATA_KEYS && content != null) {
                    val candidates = values.getOrPut(key) { mutableListOf() }
                    if (candidates.size < MAX_VALUES_PER_KEY) candidates += content.take(MAX_ATTRIBUTE_CHARS)
                }
            } else if (!closing && name == "title" && title == null) {
                val close = html.indexOf("</title", end + 1, ignoreCase = true)
                if (close < 0) break
                val candidate = html.substring(end + 1, minOf(close, end + 1 + MAX_TITLE_CHARS))
                title = candidate.takeIf { '<' !in it }
                position = close
                continue
            } else if (!closing && (name == "script" || name == "style")) {
                val close = html.indexOf("</$name", end + 1, ignoreCase = true)
                if (close < 0) break
                position = close
                continue
            }
            position = end + 1
        }
        return Metadata(values.mapValues { (_, candidates) -> candidates.toList() }, title, charset)
    }

    private fun tagEnd(
        html: String,
        start: Int,
    ): Int {
        var quote: Char? = null
        val limit = minOf(html.length, start + MAX_TAG_CHARS)
        var cursor = start + 1
        while (cursor < limit) {
            val char = html[cursor]
            when {
                quote != null && char == quote -> quote = null
                quote == null && (char == '"' || char == '\'') -> quote = char
                quote == null && char == '>' -> return cursor
            }
            cursor++
        }
        return -1
    }

    private fun attributes(
        html: String,
        start: Int,
        end: Int,
    ): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var cursor = start
        while (cursor < end && result.size < MAX_ATTRIBUTES) {
            while (cursor < end && (html[cursor].isWhitespace() || html[cursor] == '/')) cursor++
            val nameStart = cursor
            while (cursor < end && isAttributeNameChar(html[cursor])) cursor++
            if (cursor == nameStart) {
                cursor++
                continue
            }
            val name = html.substring(nameStart, cursor).take(MAX_ATTRIBUTE_CHARS).lowercase()
            while (cursor < end && html[cursor].isWhitespace()) cursor++
            if (cursor >= end || html[cursor] != '=') continue
            cursor++
            while (cursor < end && html[cursor].isWhitespace()) cursor++
            if (cursor >= end) break
            val quote = html[cursor].takeIf { it == '"' || it == '\'' }
            if (quote != null) cursor++
            val valueStart = cursor
            val valueLimit = minOf(end, valueStart + MAX_ATTRIBUTE_CHARS)
            if (quote != null) {
                while (cursor < valueLimit && html[cursor] != quote) cursor++
            } else {
                while (cursor < valueLimit && isUnquotedValueChar(html[cursor])) cursor++
            }
            result.putIfAbsent(name, html.substring(valueStart, cursor))
            if (cursor == valueLimit && cursor < end) break
            if (quote != null && cursor < end && html[cursor] == quote) cursor++
        }
        return result
    }

    private fun isAttributeNameChar(char: Char): Boolean = !char.isWhitespace() && char != '=' && char != '>' && char != '/' && char != '"' && char != '\'' && char != '<'

    private fun isUnquotedValueChar(char: Char): Boolean = !char.isWhitespace() && char != '"' && char != '\'' && char != '`' && char != '=' && char != '<' && char != '>'
}
