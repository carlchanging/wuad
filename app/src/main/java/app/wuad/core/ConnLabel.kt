package app.wuad.core

object ConnLabel {
    fun title(app: String, host: String, net: String, result: String): String {
        val h = host.ifBlank { "?" }
        val n = net.uppercase().ifBlank { "TCP" }
        val r = result.ifBlank { "DIRECT" }
        return if (app.isBlank()) "$h $n → $r" else "$app · $h $n → $r"
    }

    fun sub(rule: String, port: String, process: String = ""): String =
        listOf(rule.ifBlank { "MATCH" }, process, port.takeIf { it.isNotBlank() }?.let { ":$it" } ?: "")
            .filter { it.isNotBlank() }
            .joinToString(" · ")

    fun destCandidates(dstIp: String, fakeIp: String): List<String> {
        val out = LinkedHashSet<String>()
        if (usable(dstIp)) out.add(dstIp)
        if (usable(fakeIp)) out.add(fakeIp)
        return out.toList()
    }

    fun dualSuffix(uid: Int, label: String): String {
        if (label.isBlank()) return ""
        return if (uid >= 100000) "$label(分身)" else label
    }

    fun isFakeIp(ip: String): Boolean = ip.startsWith("198.18.")

    private fun usable(ip: String): Boolean {
        if (ip.isBlank() || ip == "0.0.0.0" || ip == "::" || ip == "::1") return false
        if (ip.startsWith("127.")) return false
        return true
    }
}
