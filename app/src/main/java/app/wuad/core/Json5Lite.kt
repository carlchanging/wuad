package app.wuad.core

/** Enough JSON5 → JSON for GKD subscriptions: comments, unquoted keys, single quotes. */
object Json5Lite {
    fun toJson(src: String): String {
        val t = strip(src)
        val out = StringBuilder(t.length + 64)
        var i = 0
        var inStr = false
        var quote = '"'
        while (i < t.length) {
            val c = t[i]
            if (inStr) {
                if (c == '\\') {
                    out.append(c)
                    if (i + 1 < t.length) {
                        out.append(t[i + 1])
                        i += 2
                        continue
                    }
                    i++
                    continue
                }
                if (c == quote) {
                    out.append('"')
                    inStr = false
                    i++
                    continue
                }
                if (c == '"' && quote == '\'') out.append("\\\"") else out.append(c)
                i++
                continue
            }
            if (c == '\'' || c == '"') {
                inStr = true
                quote = c
                out.append('"')
                i++
                continue
            }
            if (c == '{' || c == ',' || c == '[') {
                out.append(c)
                i++
                while (i < t.length && t[i].isWhitespace()) {
                    out.append(t[i])
                    i++
                }
                if (i < t.length && (t[i].isLetter() || t[i] == '_')) {
                    val start = i
                    while (i < t.length && (t[i].isLetterOrDigit() || t[i] == '_')) i++
                    val ident = t.substring(start, i)
                    var j = i
                    while (j < t.length && t[j].isWhitespace()) j++
                    if (j < t.length && t[j] == ':') {
                        out.append('"').append(ident).append('"')
                    } else {
                        out.append(ident)
                    }
                }
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private fun strip(src: String): String {
        val noBlock = src.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        val noLine = noBlock.replace(Regex("(?m)//.*?$"), "")
        return noLine.replace(Regex(",\\s*([}\\]])"), "$1")
    }
}
