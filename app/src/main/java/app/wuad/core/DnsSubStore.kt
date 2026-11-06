package app.wuad.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class DnsSub(
    val id: String,
    var name: String,
    var url: String,
    val preset: Boolean,
    var enabled: Boolean
)

/**
 * DNS 过滤订阅。多份合并进 Blocklist。
 * 预置 anti-AD 默认开；Peter Lowe 默认关（几乎被前两份吃掉）；AdRules 要自己打开。
 */
object DnsSubStore {
    const val ANTI_AD = "anti-ad"
    const val TRACK = "track"
    const val ADRULES = "adrules"

    const val ANTI_AD_URL = "https://anti-ad.net/domains.txt"
    const val TRACK_URL = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_3.txt"
    const val ADRULES_URL = "https://adguardteam.github.io/HostlistsRegistry/assets/filter_29.txt"

    private lateinit var app: Context
    private val items = ArrayList<DnsSub>()

    fun factory(): List<DnsSub> = listOf(
        DnsSub(ANTI_AD, "anti-AD", ANTI_AD_URL, true, true),
        DnsSub(TRACK, "Peter Lowe 跟踪", TRACK_URL, true, false),
        DnsSub(ADRULES, "AdRules DNS", ADRULES_URL, true, false)
    )

    fun init(ctx: Context) {
        app = ctx.applicationContext
        load()
    }

    fun all(): List<DnsSub> = synchronized(items) { items.toList() }

    fun enabled(): List<DnsSub> = all().filter { it.enabled && it.url.isNotBlank() }

    fun file(ctx: Context, id: String): File = when (id) {
        ANTI_AD -> File(ctx.filesDir, "anti-ad.txt")
        TRACK -> File(ctx.filesDir, "tracking.txt")
        else -> File(ctx.filesDir, "dns-sub-$id.txt")
    }

    fun add(name: String, url: String): DnsSub? {
        val u = normalize(url) ?: return null
        val n = name.trim().ifEmpty { hostLabel(u) }
        val item = DnsSub("s-" + UUID.randomUUID().toString().take(8), n, u, false, true)
        synchronized(items) { items.add(item) }
        persist()
        return item
    }

    fun edit(id: String, name: String, url: String): Boolean {
        val u = normalize(url) ?: return false
        val hit = synchronized(items) { items.find { it.id == id } } ?: return false
        hit.name = name.trim().ifEmpty { hostLabel(u) }
        hit.url = u
        persist()
        syncLegacy(hit)
        return true
    }

    fun setEnabled(id: String, on: Boolean) {
        val hit = synchronized(items) { items.find { it.id == id } } ?: return
        hit.enabled = on
        persist()
    }

    fun delete(id: String) {
        synchronized(items) { items.removeAll { it.id == id && !it.preset } }
        persist()
        try {
            file(app, id).delete()
        } catch (_: Exception) {
        }
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
        if (s.startsWith("https://") || s.startsWith("http://")) return s
        return null
    }

    private fun hostLabel(url: String): String {
        return try {
            java.net.URI(url).host?.removePrefix("www.") ?: "订阅"
        } catch (_: Exception) {
            "订阅"
        }
    }

    private fun syncLegacy(hit: DnsSub) {
        if (!::app.isInitialized) return
        when (hit.id) {
            ANTI_AD -> Prefs.antiAdUrl = hit.url
            TRACK -> Prefs.trackUrl = hit.url
        }
    }

    private fun load() {
        items.clear()
        val raw = app.getSharedPreferences("wuad", Context.MODE_PRIVATE).getString("dns_subs", "")
        if (raw.isNullOrBlank()) {
            seedFromLegacy()
            persist()
            return
        }
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                items.add(
                    DnsSub(
                        o.optString("id", "s-$i"),
                        o.optString("name"),
                        o.optString("url"),
                        o.optBoolean("preset", false),
                        o.optBoolean("enabled", true)
                    )
                )
            }
        } catch (_: Exception) {
            items.clear()
            seedFromLegacy()
        }
        if (items.isEmpty()) seedFromLegacy()
        ensurePresets()
        retireTrackOnce()
    }

    private fun retireTrackOnce() {
        val sp = app.getSharedPreferences("wuad", Context.MODE_PRIVATE)
        if (sp.getBoolean("dns_track_off_v72", false)) return
        items.find { it.id == TRACK }?.enabled = false
        persist()
        sp.edit().putBoolean("dns_track_off_v72", true).apply()
    }

    private fun seedFromLegacy() {
        items.addAll(factory())
        items.find { it.id == ANTI_AD }?.url = try { Prefs.antiAdUrl } catch (_: Exception) { ANTI_AD_URL }
        items.find { it.id == TRACK }?.url = try { Prefs.trackUrl } catch (_: Exception) { TRACK_URL }
    }

    private fun ensurePresets() {
        val have = items.map { it.id }.toSet()
        factory().forEach { p ->
            if (p.id !in have) items.add(p)
        }
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
                        .put("enabled", d.enabled)
                )
            }
        }
        app.getSharedPreferences("wuad", Context.MODE_PRIVATE).edit().putString("dns_subs", arr.toString()).apply()
        items.find { it.id == ANTI_AD }?.let { Prefs.antiAdUrl = it.url }
        items.find { it.id == TRACK }?.let { Prefs.trackUrl = it.url }
    }
}
