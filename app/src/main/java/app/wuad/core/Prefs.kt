package app.wuad.core

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    const val GKD_DEFAULT_URL =
        "https://ghfast.top/https://raw.githubusercontent.com/Lin-arm/GKD_subscription/main/dist/gkd.json5"
    const val GKD_LEGACY_URL =
        "https://cdn.jsdelivr.net/gh/Lin-arm/GKD_subscription@main/dist/gkd.json5"
    val GKD_MIRRORS = listOf(
        "https://ghfast.top/https://raw.githubusercontent.com/Lin-arm/GKD_subscription/main/dist/gkd.json5",
        "https://raw.gitmirror.com/Lin-arm/GKD_subscription/main/dist/gkd.json5",
        "https://cdn.jsdelivr.net/gh/Lin-arm/GKD_subscription@main/dist/gkd.json5",
        "https://mirror.ghproxy.com/https://raw.githubusercontent.com/Lin-arm/GKD_subscription/main/dist/gkd.json5"
    )

    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.applicationContext.getSharedPreferences("wuad", Context.MODE_PRIVATE)
    }

    var vpnWanted: Boolean
        get() = sp.getBoolean("vpn", false)
        set(v) { sp.edit().putBoolean("vpn", v).commit() }

    var dnsOn: Boolean
        get() = sp.getBoolean("dns", true)
        set(v) { sp.edit().putBoolean("dns", v).apply() }

    var skipOn: Boolean
        get() = sp.getBoolean("skip", true)
        set(v) { sp.edit().putBoolean("skip", v).apply() }

    var routeOn: Boolean
        get() = sp.getBoolean("route", true)
        set(v) { sp.edit().putBoolean("route", v).apply() }

    var subUrl: String
        get() = sp.getString("url_sub", "") ?: ""
        set(v) { sp.edit().putString("url_sub", v.trim()).apply() }

    var mode: String
        get() = sp.getString("mode", "rule") ?: "rule"
        set(v) { sp.edit().putString("mode", v).apply() }

    var node: String
        get() = sp.getString("node", "AUTO") ?: "AUTO"
        set(v) { sp.edit().putString("node", v).apply() }

    fun gkdCatOn(key: Int): Boolean {
        migrateGkdCatsOnce()
        val cat = GkdCats.all.find { it.key == key } ?: return false
        return sp.getBoolean("gkd_cat_$key", cat.defaultOn)
    }

    fun setGkdCatOn(key: Int, on: Boolean) {
        migrateGkdCatsOnce()
        sp.edit().putBoolean("gkd_cat_$key", on).apply()
    }

    fun featureOff(kind: String): Set<String> {
        val key = "off_$kind"
        if (!sp.contains(key) && kind == "gkd") {
            return HashSet(sp.getStringSet("gkd_ignore", emptySet()) ?: emptySet())
        }
        return HashSet(sp.getStringSet(key, emptySet()) ?: emptySet())
    }

    fun featureOff(kind: String, pkg: String): Boolean =
        featureOff(kind).any { it.equals(pkg, true) }

    fun setFeatureOff(kind: String, pkg: String, off: Boolean) {
        val set = featureOff(kind).toMutableSet()
        if (off) set.add(pkg) else set.remove(pkg)
        sp.edit().putStringSet("off_$kind", set).apply()
    }

    fun gkdIgnored(): Set<String> = featureOff("gkd")
    fun gkdIgnored(pkg: String): Boolean = featureOff("gkd", pkg)

    fun gkdCatOn(group: String): Boolean = gkdCatOn(GkdCats.of(group).key)

    private fun migrateGkdCatsOnce() {
        if (sp.getBoolean("gkd_cat_v11", false)) return
        val e = sp.edit()
        e.putBoolean("gkd_cat_0", sp.getBoolean("gkd_splash", true))
        e.putBoolean("gkd_cat_1", sp.getBoolean("gkd_teen", false))
        e.putBoolean("gkd_cat_2", sp.getBoolean("gkd_update", false))
        e.putBoolean("gkd_cat_v11", true)
        e.apply()
    }

    fun appPolicy(pkg: String): String {
        if (pkg == "app.wuad") return "exclude"
        return policies()[pkg] ?: "default"
    }

    fun setAppPolicy(pkg: String, policy: String) {
        val map = policies().toMutableMap()
        if (policy == "default") map.remove(pkg) else map[pkg] = policy
        map["app.wuad"] = "exclude"
        val o = org.json.JSONObject()
        map.forEach { (k, v) -> o.put(k, v) }
        val ex = map.filter { it.value == "exclude" }.keys.toMutableSet()
        ex.add("app.wuad")
        sp.edit().putString("app_pol", o.toString()).putStringSet("excluded", ex).apply()
    }

    fun policies(): Map<String, String> {
        val raw = sp.getString("app_pol", "") ?: ""
        if (raw.isBlank()) {
            return excluded().associateWith { "exclude" }
        }
        return try {
            val o = org.json.JSONObject(raw)
            val out = HashMap<String, String>()
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = o.optString(k)
            }
            out
        } catch (_: Exception) {
            excluded().associateWith { "exclude" }
        }
    }

    var dnsUpstream: String
        get() = sp.getString("dns_up", "223.5.5.5") ?: "223.5.5.5"
        set(v) { sp.edit().putString("dns_up", v.trim()).apply() }

    var dnsUpId: String
        get() = sp.getString("dns_up_id", "") ?: ""
        set(v) { sp.edit().putString("dns_up_id", v).apply() }

    var customBlock: String
        get() = sp.getString("custom_block", "") ?: ""
        set(v) { sp.edit().putString("custom_block", v).apply() }

    var customAllow: String
        get() = sp.getString("custom_allow", "") ?: ""
        set(v) { sp.edit().putString("custom_allow", v).apply() }

    fun excluded(): MutableSet<String> =
        HashSet(sp.getStringSet("excluded", setOf("app.wuad")) ?: setOf("app.wuad"))

    fun setExcluded(pkg: String, exclude: Boolean) {
        val set = excluded()
        if (exclude) set.add(pkg) else set.remove(pkg)
        set.add("app.wuad")
        sp.edit().putStringSet("excluded", set).apply()
    }

    var antiAdUrl: String
        get() = sp.getString("url_anti", "https://anti-ad.net/domains.txt") ?: "https://anti-ad.net/domains.txt"
        set(v) { sp.edit().putString("url_anti", v.trim()).apply() }

    var trackUrl: String
        get() = sp.getString("url_track", "https://adguardteam.github.io/HostlistsRegistry/assets/filter_3.txt")
            ?: "https://adguardteam.github.io/HostlistsRegistry/assets/filter_3.txt"
        set(v) { sp.edit().putString("url_track", v.trim()).apply() }

    var gkdUrl: String
        get() = sp.getString("url_gkd", GKD_DEFAULT_URL) ?: GKD_DEFAULT_URL
        set(v) { sp.edit().putString("url_gkd", v.trim()).apply() }

    fun gkdFetchUrls(): List<String> {
        val cur = gkdUrl.trim()
        val out = ArrayList<String>()
        if (cur.isNotBlank()) out += cur
        if (cur == GKD_LEGACY_URL || cur.isBlank()) out += GKD_DEFAULT_URL
        out += GKD_MIRRORS
        return out.distinct()
    }

    var checkOnOpen: Boolean
        get() = sp.getBoolean("check_on_open", false)
        set(v) { sp.edit().putBoolean("check_on_open", v).apply() }

    fun lastUpdate(key: String): Long = sp.getLong("upd_$key", 0)
    fun setLastUpdate(key: String, t: Long) { sp.edit().putLong("upd_$key", t).apply() }

    fun geoUrl(key: String, fallback: String): String = sp.getString("geo_url_$key", fallback) ?: fallback
    fun setGeoUrl(key: String, url: String) { sp.edit().putString("geo_url_$key", url.trim()).apply() }

    fun lines(raw: String): List<String> =
        raw.split('\n', ',', ';', ' ', '\t')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && '.' in it }
}
