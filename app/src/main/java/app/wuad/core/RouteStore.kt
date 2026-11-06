package app.wuad.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class RouteRule(
    val id: String,
    var type: String,
    var payload: String,
    var policy: String,
    var url: String,
    var enabled: Boolean,
    val preset: Boolean,
    var count: Int = 0
)

object RouteStore {
    private const val REJECT_URL = "https://cdn.jsdelivr.net/gh/Loyalsoldier/clash-rules@release/reject.txt"
    private const val PROXY_URL = "https://cdn.jsdelivr.net/gh/Loyalsoldier/clash-rules@release/proxy.txt"
    private const val DIRECT_URL = "https://cdn.jsdelivr.net/gh/Loyalsoldier/clash-rules@release/direct.txt"

    private lateinit var app: Context
    private val ready = AtomicBoolean(false)
    private val rules = ArrayList<RouteRule>()

    val presetOrder = listOf(
        "ls-reject", "ls-proxy", "ls-direct",
        "site-gfw", "site-cn", "geo-private", "geo-cn", "match"
    )

    fun factory(): List<RouteRule> = listOf(
        RouteRule("ls-reject", "PROVIDER", "Loyalsoldier 拒绝列表", "REJECT", REJECT_URL, false, true),
        RouteRule("ls-proxy", "PROVIDER", "Loyalsoldier 代理列表", "PROXY", PROXY_URL, false, true),
        RouteRule("ls-direct", "PROVIDER", "Loyalsoldier 直连列表", "DIRECT", DIRECT_URL, false, true),
        RouteRule("site-gfw", "GEOSITE", "gfw", "PROXY", GeoStore.URL_GEOSITE, true, true),
        RouteRule("site-cn", "GEOSITE", "cn", "DIRECT", GeoStore.URL_GEOSITE, true, true),
        RouteRule("geo-private", "GEOIP", "private", "DIRECT", GeoStore.URL_GEOIP_DAT, true, true),
        RouteRule("geo-cn", "GEOIP", "CN", "DIRECT", GeoStore.URL_GEOIP_DAT, true, true),
        RouteRule("match", "MATCH", "*", "PROXY", "", true, true)
    )

    fun ordered(current: List<RouteRule>): List<RouteRule> {
        val rank = presetOrder.withIndex().associate { it.value to it.index }
        val presets = current.filter { it.id in rank }.sortedBy { rank.getValue(it.id) }
        val custom = current.filter { it.id !in rank }
        return presets.filter { it.id != "match" } + custom + presets.filter { it.id == "match" }
    }

    @Synchronized
    fun init(ctx: Context) {
        if (ready.get()) return
        app = ctx.applicationContext
        load()
        ready.set(true)
    }

    fun all(): List<RouteRule> = synchronized(rules) { rules.toList() }

    fun find(id: String): RouteRule? = synchronized(rules) { rules.find { it.id == id } }

    fun add(type: String, payload: String, policy: String, url: String, enabled: Boolean): RouteRule {
        val item = RouteRule(
            "r-" + UUID.randomUUID().toString().take(8),
            type.uppercase(),
            payload.trim(),
            policy.uppercase(),
            url.trim(),
            enabled,
            false
        )
        synchronized(rules) { rules.add(item) }
        persist()
        return item
    }

    fun edit(id: String, type: String, payload: String, policy: String, url: String, enabled: Boolean) {
        find(id)?.apply {
            this.type = type.uppercase()
            this.payload = payload.trim()
            this.policy = policy.uppercase()
            this.url = url.trim()
            this.enabled = enabled
        }
        persist()
    }

    fun setEnabled(id: String, on: Boolean) {
        find(id)?.enabled = on
        persist()
    }

    fun delete(id: String) {
        synchronized(rules) { rules.removeAll { it.id == id } }
        File(dir(), "$id.txt").delete()
        persist()
    }

    fun restorePresets() {
        val custom = synchronized(rules) { rules.filter { !it.preset } }
        synchronized(rules) {
            rules.clear()
            rules.addAll(ordered(factory() + custom))
        }
        persist()
    }

    fun disablePresets() {
        synchronized(rules) { rules.filter { it.preset }.forEach { it.enabled = false } }
        persist()
    }

    fun enablePresets() {
        synchronized(rules) { rules.filter { it.preset }.forEach { it.enabled = true } }
        persist()
    }

    fun presetsOn(): Boolean = synchronized(rules) { rules.any { it.preset && it.enabled } }

    fun file(id: String) = File(dir(), "$id.txt")

    fun setCount(id: String, n: Int) {
        find(id)?.count = n
        persist()
    }

    fun clashLines(): List<String> {
        return all().filter { it.enabled }.map { r ->
            when (r.type) {
                "PROVIDER" -> "# provider ${r.payload} -> ${r.policy} ${r.url}"
                "MATCH" -> "MATCH,${r.policy}"
                else -> "${r.type},${r.payload},${r.policy}"
            }
        }
    }

    private fun dir() = File(app.filesDir, "route").apply { mkdirs() }

    private fun storeFile() = File(app.filesDir, "route-rules.json")

    private fun load() {
        rules.clear()
        val f = storeFile()
        if (!f.exists()) {
            rules.addAll(factory())
            persist()
            return
        }
        val arr = JSONArray(f.readText())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            rules.add(
                RouteRule(
                    o.getString("id"),
                    o.optString("type"),
                    o.optString("payload"),
                    o.optString("policy"),
                    o.optString("url"),
                    o.optBoolean("enabled", true),
                    o.optBoolean("preset", false),
                    o.optInt("count", 0)
                )
            )
        }
        if (rules.none { it.preset }) {
            rules.addAll(0, factory())
            persist()
            return
        }
        fillBlankPresetUrls()
        val next = ordered(rules.toList())
        if (next.map { it.id } != rules.map { it.id }) {
            rules.clear()
            rules.addAll(next)
            persist()
        }
    }

    private fun fillBlankPresetUrls() {
        val fac = factory().associateBy { it.id }
        var dirty = false
        rules.forEach { r ->
            val stock = fac[r.id] ?: return@forEach
            if (r.preset && r.url.isBlank() && stock.url.isNotBlank()) {
                r.url = stock.url
                dirty = true
            }
        }
        if (dirty) persist()
    }

    private fun persist() {
        val arr = JSONArray()
        synchronized(rules) {
            rules.forEach { r ->
                arr.put(
                    JSONObject()
                        .put("id", r.id)
                        .put("type", r.type)
                        .put("payload", r.payload)
                        .put("policy", r.policy)
                        .put("url", r.url)
                        .put("enabled", r.enabled)
                        .put("preset", r.preset)
                        .put("count", r.count)
                )
            }
        }
        storeFile().writeText(arr.toString())
    }
}
