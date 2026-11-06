package app.wuad.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class DnsUp(
    val id: String,
    var name: String,
    var url: String,
    val preset: Boolean
)

object DnsUpStore {
    const val ALI = "plain-ali"
    const val ALI_URL = "223.5.5.5"
    private val retired = setOf("plain-google", "doh-cf", "doh-google", "dot-cf")

    private lateinit var app: Context
    private val items = ArrayList<DnsUp>()

    fun factory(): List<DnsUp> = listOf(
        DnsUp(ALI, "阿里 明文", ALI_URL, true),
        DnsUp("doh-ali", "DoH 阿里", "https://dns.alidns.com/dns-query", true),
        DnsUp("plain-cf", "Cloudflare 明文", "1.1.1.1", true)
    )

    fun init(ctx: Context) {
        app = ctx.applicationContext
        load()
    }

    fun all(): List<DnsUp> = synchronized(items) { items.toList() }

    fun add(name: String, url: String): DnsUp? {
        val n = name.trim().ifEmpty { url.trim() }
        val u = normalize(url) ?: return null
        val item = DnsUp("u-" + UUID.randomUUID().toString().take(8), n, u, false)
        synchronized(items) { items.add(item) }
        persist()
        return item
    }

    fun edit(id: String, name: String, url: String): Boolean {
        val u = normalize(url) ?: return false
        val hit = synchronized(items) { items.find { it.id == id } } ?: return false
        hit.name = name.trim().ifEmpty { u }
        hit.url = u
        persist()
        if (Prefs.dnsUpstream == hit.url || Prefs.dnsUpId == id) {
            Prefs.dnsUpstream = u
            Prefs.dnsUpId = id
        }
        return true
    }

    fun delete(id: String) {
        synchronized(items) { items.removeAll { it.id == id } }
        persist()
        if (Prefs.dnsUpId == id) {
            val first = all().firstOrNull()
            Prefs.dnsUpId = first?.id ?: ""
            Prefs.dnsUpstream = first?.url ?: ALI_URL
        }
    }

    fun select(id: String) {
        val hit = all().find { it.id == id } ?: return
        Prefs.dnsUpId = hit.id
        Prefs.dnsUpstream = hit.url
    }

    fun restorePresets() {
        val custom = synchronized(items) { items.filter { !it.preset } }
        synchronized(items) {
            items.clear()
            items.addAll(factory())
            items.addAll(custom)
        }
        persist()
    }

    fun normalize(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        if (s.startsWith("https://") || s.startsWith("tls://") || s.startsWith("quic://") || s.startsWith("http://")) {
            return s
        }
        val host = s.substringBefore("/")
        val ip = host.substringBefore(":")
        if (ip.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}")) || host.contains('.')) return host
        return null
    }

    /** DNS-only 隧道只转发 UDP/53，DoH/DoT 取主机名当解析目标。 */
    fun udpHost(raw: String = Prefs.dnsUpstream): String {
        val s = raw.trim()
        val host = when {
            s.contains("://") -> s.substringAfter("://").substringBefore("/").substringBefore(":")
            else -> s.substringBefore("/").substringBefore(":")
        }
        return host.ifBlank { "223.5.5.5" }
    }

    private fun load() {
        items.clear()
        val raw = app.getSharedPreferences("wuad", Context.MODE_PRIVATE).getString("dns_ups", "")
        if (raw.isNullOrBlank()) {
            items.addAll(factory())
            persist()
            return
        }
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                items.add(
                    DnsUp(
                        o.optString("id", "u-$i"),
                        o.optString("name"),
                        o.optString("url"),
                        o.optBoolean("preset", false)
                    )
                )
            }
        } catch (_: Exception) {
            items.clear()
            items.addAll(factory())
        }
        if (items.isEmpty()) items.addAll(factory())
        slimPresets()
    }

    private fun slimPresets() {
        val custom = items.filter { !it.preset }
        val have = items.filter { it.preset && it.id !in retired }.associateBy { it.id }
        items.clear()
        factory().forEach { f -> items += have[f.id] ?: f }
        items += custom
        persist()
        pickDefaultIfNeeded()
    }

    private fun pickDefaultIfNeeded() {
        val id = try { Prefs.dnsUpId } catch (_: Exception) { "" }
        val up = try { Prefs.dnsUpstream } catch (_: Exception) { "" }
        val ids = items.map { it.id }.toSet()
        if (id == "plain-cf" && id in ids) return
        val gone = id in retired || id !in ids || id.isBlank()
        val foreignGone = up in setOf(
            "8.8.8.8",
            "https://8.8.8.8/dns-query",
            "https://1.1.1.1/dns-query",
            "tls://1.1.1.1"
        )
        val oldDefault = id.isBlank() && (up.isBlank() || up == "1.1.1.1")
        if (gone || foreignGone || oldDefault) select(ALI)
    }

    private fun persist() {
        val arr = JSONArray()
        synchronized(items) {
            items.forEach { d ->
                arr.put(
                    JSONObject()
                        .put("id", d.id)
                        .put("name", d.name)
                        .put("url", d.url)
                        .put("preset", d.preset)
                )
            }
        }
        app.getSharedPreferences("wuad", Context.MODE_PRIVATE).edit().putString("dns_ups", arr.toString()).apply()
    }
}
