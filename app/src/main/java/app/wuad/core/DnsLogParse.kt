package app.wuad.core

import org.json.JSONObject

data class DnsHit(
    val host: String,
    val qtype: String,
    val result: String,
    val extra: String,
    val srcIp: String = "",
    val srcPort: Int = 0
)

object DnsLogParse {
    private val hostRe = Regex("([a-zA-Z0-9._-]+\\.[a-zA-Z]{2,})(?::(\\d+))?")
    private val typeRe = Regex("\\b(A|AAAA|CNAME|HTTPS|SVCB|MX|TXT|NS)\\b", RegexOption.IGNORE_CASE)
    private val ipRe = Regex("\\b(\\d{1,3}(?:\\.\\d{1,3}){3})\\b")
    private val fakeRe = Regex("\\b(198\\.18\\.\\d{1,3}\\.\\d{1,3})\\b")
    private val srcRe = Regex("(\\d{1,3}(?:\\.\\d{1,3}){3}):(\\d+)\\s*-->")

    fun payloadOf(line: String): String {
        val s = line.trim()
        if (!s.startsWith("{")) return s
        return try {
            JSONObject(s).optString("payload").ifBlank { s }
        } catch (_: Exception) {
            s
        }
    }

    fun parse(raw: String): DnsHit? {
        val s = payloadOf(raw)
        if (s.isEmpty()) return null
        val reject = s.contains("REJECT", true)
        val dns = s.contains("[DNS]", true)
        val tcp = s.contains("[TCP]", true)
        val udp = s.contains("[UDP]", true)
        val fail = s.contains("resolve failed", true) || s.contains("couldn't resolve", true) ||
            s.contains("dns lookup failed", true)
        if (!reject && !dns && !tcp && !udp && !fail) return null
        val host = hostRe.find(s)?.groupValues?.get(1)
            ?: fakeRe.find(s)?.value
            ?: ipRe.find(s)?.value
            ?: return null
        val qtype = typeRe.find(s)?.value?.uppercase() ?: when {
            dns -> "A"
            udp -> "UDP"
            else -> "TCP"
        }
        val result = when {
            reject -> "REJECT"
            fail -> "FAIL"
            fakeRe.find(s) != null -> "FAKE-IP ${fakeRe.find(s)!!.value}"
            ipRe.findAll(s).lastOrNull() != null -> ipRe.findAll(s).last().value
            s.contains("-->") -> s.substringAfter("-->").trim().take(40)
            else -> "HIT"
        }
        val extra = buildString {
            if (dns) append("DNS ")
            if (tcp) append("TCP ")
            if (udp) append("UDP ")
            if (s.contains("PROXY", true)) append("PROXY ")
            if (s.contains("DIRECT", true)) append("DIRECT ")
            if (s.contains("antiad", true) || s.contains("RuleSet", true)) append("规则集")
        }.trim()
        val src = srcRe.find(s)
        return DnsHit(
            host,
            qtype,
            result,
            extra,
            src?.groupValues?.get(1).orEmpty(),
            src?.groupValues?.get(2)?.toIntOrNull() ?: 0
        )
    }
}
