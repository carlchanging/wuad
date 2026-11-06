package app.wuad.core

object HostlistParse {
    fun host(line: String): String? {
        var s = line.trim()
        if (s.isEmpty() || s.startsWith("#") || s.startsWith("!") || s.startsWith("[")) return null
        if (s.startsWith("@@")) return null
        if (s.startsWith("||")) {
            s = s.removePrefix("||").substringBefore('^').substringBefore('/').substringBefore('$')
            return clean(s)
        }
        val parts = s.split(Regex("\\s+"))
        val raw = when (parts[0]) {
            "0.0.0.0", "127.0.0.1", "::", "::1" -> parts.getOrNull(1)
            else -> parts[0]
        }
        return clean(raw)
    }

    private fun clean(raw: String?): String? {
        val h = raw?.trim()?.trim('.')?.lowercase() ?: return null
        if (h.isEmpty() || '*' in h || '/' in h || h == "localhost") return null
        if (!h.contains('.')) return null
        return h
    }
}
