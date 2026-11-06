package app.wuad.core

import android.content.Context
import java.io.File

object RouteUpdater {
    fun updateOne(rule: RouteRule): String {
        if (rule.type != "PROVIDER" || rule.url.isBlank()) {
            return "${rule.payload} 不是可下载列表"
        }
        val raw = File(RouteStore.file(rule.id).parentFile, rule.id + ".raw")
        RemoteFiles.download(rule.url, raw)
        val hosts = parsePayload(raw.readText())
        val dest = RouteStore.file(rule.id)
        dest.writeText(hosts.joinToString("\n"))
        RouteStore.setCount(rule.id, hosts.size)
        Prefs.setLastUpdate("route_${rule.id}", System.currentTimeMillis())
        Prefs.setLastUpdate("route", System.currentTimeMillis())
        return "${rule.payload} 已更新 ${hosts.size} 条"
    }

    fun updateEnabled(ctx: Context? = null): String {
        val parts = ArrayList<String>()
        RouteStore.all().filter { it.enabled && it.type == "PROVIDER" && it.url.isNotBlank() }.forEach { r ->
            parts.add(updateOne(r))
        }
        if (ctx != null) {
            val geoUrls = RouteStore.all()
                .filter { it.enabled && it.url.isNotBlank() && (it.type == "GEOSITE" || it.type == "GEOIP") }
                .map { it.url }
                .distinct()
            geoUrls.forEach { url ->
                val pack = GeoStore.packForUrl(url) ?: return@forEach
                try {
                    parts.add(GeoStore.update(ctx, pack))
                } catch (e: Exception) {
                    parts.add("${pack.title} 失败：${e.message}")
                }
            }
            if (geoUrls.isEmpty()) {
                try { parts.add(GeoStore.ensure(ctx)) } catch (e: Exception) { parts.add("geo：${e.message}") }
            }
        }
        if (parts.isEmpty()) return "没有可更新的分流订阅"
        return parts.joinToString("\n")
    }

    fun repairStored() {
        RouteStore.all().filter { it.type == "PROVIDER" }.forEach { r ->
            val dest = RouteStore.file(r.id)
            val raw = File(dest.parentFile, r.id + ".raw")
            val hosts = when {
                raw.exists() && raw.length() > 4 -> parsePayload(raw.readText())
                dest.exists() && dest.length() > 4 -> dest.readLines().map { plusDot(it) }.filter { it.isNotBlank() }
                else -> emptyList()
            }
            if (hosts.isEmpty()) return@forEach
            dest.writeText(hosts.joinToString("\n"))
            RouteStore.setCount(r.id, hosts.size)
        }
    }

    fun parsePayload(text: String): List<String> {
        val out = ArrayList<String>(80000)
        text.lineSequence().forEach { line ->
            var s = line.trim()
            if (s.isEmpty() || s.startsWith("#") || s.startsWith("payload:")) return@forEach
            if (s.startsWith("-")) s = s.removePrefix("-").trim()
            s = s.trim('\'', '"', '`')
            if (s.contains(",") && !s.startsWith("+.") && !s.startsWith("*.")) {
                val bits = s.split(',')
                if (bits.size >= 2) s = bits[1].trim()
            }
            s = plusDot(s)
            if (s.isNotEmpty() && !s.endsWith(":")) out.add(s)
        }
        return out
    }

    /** Loyalsoldier 的 `+.google.com` 才匹配 www；光写 google.com 只中根域。 */
    fun plusDot(raw: String): String {
        var s = raw.trim().lowercase().trimEnd('.')
        if (s.isEmpty() || s.startsWith("#")) return ""
        if (s.startsWith("+.") || s.startsWith("*.")) return s
        if (s.startsWith(".")) s = "+$s"
        if (s.startsWith("+.")) return s
        if (s.startsWith("geosite:") || s.startsWith("rule-set:") || s.startsWith("geoip:")) return s
        if (IPV4.matches(s)) return s
        if (!s.contains('.')) return s
        return "+.$s"
    }

    private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}(/\d{1,2})?$""")
}
