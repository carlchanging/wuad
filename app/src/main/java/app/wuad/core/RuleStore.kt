package app.wuad.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

object RuleStore {
    private lateinit var app: Context
    private val ready = AtomicBoolean(false)

    @Volatile var antiAdOn: Boolean = true
    private val dnsDeleted = HashSet<String>()
    private val dnsCustom = ArrayList<DnsItem>()

    @Volatile var gkdOn: Boolean = true
    @Volatile var gkdGen: Int = 0
        private set
    private val gkdPreset = ArrayList<GkdItem>()
    private val gkdDeleted = HashSet<String>()
    private val gkdDisabled = HashSet<String>()
    private val gkdEdits = HashMap<String, GkdItem>()
    private val gkdCustom = ArrayList<GkdItem>()

    @Synchronized
    fun init(ctx: Context) {
        if (ready.get()) return
        app = ctx.applicationContext
        loadDnsOverlay()
        loadGkdPreset()
        loadGkdOverlay()
        ready.set(true)
    }

    fun isReady(): Boolean = ready.get()
    fun gkdPresetCount(): Int = gkdPreset.size

    fun reloadGkdPreset() {
        loadGkdPreset()
        bumpGkd()
    }

    fun dnsPresetOn() = antiAdOn
    fun gkdPresetOn() = gkdOn

    fun enableAntiAd(on: Boolean) {
        antiAdOn = on
        persistDns()
    }

    fun enableGkd(on: Boolean) {
        gkdOn = on
        persistGkd()
    }

    fun dnsDeleted(): Set<String> = synchronized(dnsDeleted) { HashSet(dnsDeleted) }

    fun dnsCustom(): List<DnsItem> = synchronized(dnsCustom) { dnsCustom.filter { !it.deleted } }

    fun addDns(host: String, action: String): DnsItem {
        val item = DnsItem("c-" + UUID.randomUUID().toString().take(8), host.trim().lowercase(), action, false)
        synchronized(dnsCustom) { dnsCustom.add(item) }
        persistDns()
        return item
    }

    fun editDns(id: String, host: String, action: String) {
        val h = host.trim().lowercase()
        if (id.startsWith("c-")) {
            synchronized(dnsCustom) {
                dnsCustom.find { it.id == id }?.apply {
                    this.host = h
                    this.action = action
                }
            }
        } else {
            deleteDns(id)
            addDns(h, action)
            return
        }
        persistDns()
    }

    fun deleteDns(idOrHost: String) {
        synchronized(dnsCustom) {
            val hit = dnsCustom.find { it.id == idOrHost || it.host == idOrHost }
            if (hit != null) {
                dnsCustom.removeAll { it.id == hit.id }
                persistDns()
                return
            }
        }
        synchronized(dnsDeleted) { dnsDeleted.add(idOrHost.lowercase()) }
        persistDns()
    }

    fun restoreDnsHost(host: String) {
        synchronized(dnsDeleted) { dnsDeleted.remove(host.lowercase()) }
        persistDns()
    }

    fun restoreAntiAdPreset() {
        antiAdOn = true
        synchronized(dnsDeleted) { dnsDeleted.clear() }
        persistDns()
    }

    fun removeAntiAdPreset() {
        antiAdOn = false
        persistDns()
    }

    fun gkdVisible(): List<GkdItem> {
        val out = ArrayList<GkdItem>()
        if (gkdOn) {
            for (p in gkdPreset) {
                if (p.id in gkdDeleted) continue
                val e = gkdEdits[p.id]
                out.add(mergePreset(p, e))
            }
        }
        out.addAll(gkdCustom.filter { !it.deleted })
        return out
    }

    fun addGkd(pkg: String, appName: String, group: String, match: String, enabled: Boolean): GkdItem {
        val item = GkdItem(
            "g-" + UUID.randomUUID().toString().take(8),
            pkg.trim(),
            appName.trim().ifEmpty { pkg },
            group.trim().ifEmpty { "自定义" },
            match.trim().ifEmpty { "跳过" },
            false,
            enabled
        )
        gkdCustom.add(item)
        persistGkd()
        return item
    }

    fun editGkd(id: String, pkg: String, appName: String, group: String, match: String, enabled: Boolean) {
        val custom = gkdCustom.find { it.id == id }
        if (custom != null) {
            custom.pkg = pkg.trim()
            custom.app = appName.trim().ifEmpty { pkg }
            custom.group = group.trim()
            custom.match = match.trim()
            custom.enabled = enabled
        } else {
            gkdEdits[id] = GkdItem(id, pkg.trim(), appName.trim(), group.trim(), match.trim(), true, enabled)
            if (enabled) gkdDisabled.remove(id) else gkdDisabled.add(id)
        }
        persistGkd()
    }

    fun setGkdEnabled(id: String, on: Boolean) {
        applyGkdEnabled(id, on)
        persistGkd()
    }

    fun setGkdAppEnabled(pkg: String, on: Boolean) {
        gkdVisible().filter { it.pkg == pkg }.forEach { applyGkdEnabled(it.id, on) }
        persistGkd()
    }

    private fun applyGkdEnabled(id: String, on: Boolean) {
        val custom = gkdCustom.find { it.id == id }
        if (custom != null) {
            custom.enabled = on
        } else {
            if (on) gkdDisabled.remove(id) else gkdDisabled.add(id)
            gkdEdits[id]?.enabled = on
        }
    }

    fun deleteGkd(id: String) {
        val custom = gkdCustom.find { it.id == id }
        if (custom != null) gkdCustom.remove(custom) else gkdDeleted.add(id)
        persistGkd()
    }

    fun restoreGkdPreset() {
        gkdOn = true
        gkdDeleted.clear()
        gkdDisabled.clear()
        gkdEdits.clear()
        persistGkd()
    }

    fun removeGkdPreset() {
        gkdOn = false
        persistGkd()
    }

    fun activeMatches(pkg: String): List<String> = activeItems(pkg).map { it.match }

    fun hasAppGroup(pkg: String, prefix: String): Boolean {
        if (pkg.isEmpty() || prefix.isEmpty()) return false
        return gkdVisible().any { it.pkg == pkg && it.group.startsWith(prefix) }
    }

    fun activeItems(pkg: String): List<GkdItem> {
        if (!Prefs.skipOn) return emptyList()
        return gkdVisible()
            .filter {
                it.enabled && (it.pkg == "*" || it.pkg == pkg) &&
                    (it.match.isNotBlank() || it.andMatch.isNotBlank()) &&
                    (!it.preset || Prefs.gkdCatOn(it.group))
            }
    }

    private fun loadDnsOverlay() {
        val sp = app.getSharedPreferences("wuad-rules", Context.MODE_PRIVATE)
        antiAdOn = sp.getBoolean("anti_on", true)
        dnsDeleted.clear()
        dnsDeleted.addAll(sp.getStringSet("dns_del", emptySet()) ?: emptySet())
        dnsCustom.clear()
        val arr = JSONArray(sp.getString("dns_custom", "[]"))
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            dnsCustom.add(
                DnsItem(
                    o.optString("id", "c-$i"),
                    o.optString("host"),
                    o.optString("action", "block"),
                    false
                )
            )
        }
        Prefs.lines(Prefs.customBlock).forEach { h ->
            if (dnsCustom.none { it.host == h && it.action == "block" }) {
                dnsCustom.add(DnsItem("c-legacy-b-$h", h, "block", false))
            }
        }
        Prefs.lines(Prefs.customAllow).forEach { h ->
            if (dnsCustom.none { it.host == h && it.action == "allow" }) {
                dnsCustom.add(DnsItem("c-legacy-a-$h", h, "allow", false))
            }
        }
    }

    private fun persistDns() {
        val arr = JSONArray()
        synchronized(dnsCustom) {
            dnsCustom.forEach { d ->
                arr.put(JSONObject().put("id", d.id).put("host", d.host).put("action", d.action))
            }
        }
        val blocks = dnsCustom.filter { it.action == "block" }.joinToString("\n") { it.host }
        val allows = dnsCustom.filter { it.action == "allow" }.joinToString("\n") { it.host }
        Prefs.customBlock = blocks
        Prefs.customAllow = allows
        app.getSharedPreferences("wuad-rules", Context.MODE_PRIVATE).edit()
            .putBoolean("anti_on", antiAdOn)
            .putStringSet("dns_del", HashSet(dnsDeleted))
            .putString("dns_custom", arr.toString())
            .apply()
    }

    private fun mergePreset(p: GkdItem, e: GkdItem?): GkdItem {
        if (e == null) return p.copy(enabled = p.id !in gkdDisabled, deleted = false)
        return p.copy(
            pkg = e.pkg.ifBlank { p.pkg },
            app = e.app.ifBlank { p.app },
            group = e.group.ifBlank { p.group },
            match = e.match.ifBlank { p.match },
            enabled = e.enabled,
            deleted = false,
            activity = e.activity.ifBlank { p.activity },
            action = e.action.ifBlank { p.action },
            delay = if (e.delay > 0) e.delay else p.delay,
            matchTime = if (e.matchTime > 0) e.matchTime else p.matchTime,
            actionMaximum = if (e.actionMaximum > 0) e.actionMaximum else p.actionMaximum,
            resetMatch = e.resetMatch.ifBlank { p.resetMatch },
            excludeActivity = e.excludeActivity.ifBlank { p.excludeActivity },
            excludeMatch = e.excludeMatch.ifBlank { p.excludeMatch },
            andMatch = e.andMatch.ifBlank { p.andMatch },
            disableIfGroup = e.disableIfGroup.ifBlank { p.disableIfGroup },
            matchAnyApp = p.matchAnyApp,
            matchLauncher = p.matchLauncher,
            matchSystemApp = p.matchSystemApp,
            denyApps = p.denyApps,
            allowApps = p.allowApps,
            ruleKey = p.ruleKey,
            preKeys = p.preKeys
        )
    }

    private fun readItem(o: JSONObject, preset: Boolean, fallbackId: String): GkdItem {
        return GkdItem(
            o.optString("id", fallbackId),
            o.optString("pkg"),
            o.optString("app"),
            o.optString("group"),
            o.optString("match"),
            preset,
            o.optBoolean("enabled", false),
            activity = o.optString("activity"),
            action = o.optString("action", "click"),
            delay = o.optInt("delay", 0),
            matchTime = o.optInt("matchTime", 0),
            actionMaximum = o.optInt("actionMaximum", 0),
            resetMatch = o.optString("resetMatch"),
            excludeActivity = o.optString("excludeActivity"),
            excludeMatch = o.optString("excludeMatch"),
            andMatch = o.optString("andMatch"),
            disableIfGroup = o.optString("disableIfGroup"),
            matchAnyApp = o.optBoolean("matchAnyApp", o.optString("pkg") == "*"),
            matchLauncher = o.optBoolean("matchLauncher", false),
            matchSystemApp = o.optBoolean("matchSystemApp", false),
            denyApps = o.optString("denyApps"),
            allowApps = o.optString("allowApps"),
            ruleKey = o.optInt("ruleKey", 0),
            preKeys = o.optString("preKeys")
        )
    }

    private fun loadGkdPreset() {
        gkdPreset.clear()
        val updated = File(app.filesDir, "gkd-rules.json")
        val text = if (updated.exists() && updated.length() > 32) {
            val raw = updated.readText()
            val probe = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
            if (probe.optInt("format", 1) >= GkdParser.FORMAT) {
                raw
            } else {
                updated.delete()
                app.assets.open("gkd-rules.json").bufferedReader().use { it.readText() }
            }
        } else {
            app.assets.open("gkd-rules.json").bufferedReader().use { it.readText() }
        }
        val root = JSONObject(text)
        val arr = root.getJSONArray("rules")
        for (i in 0 until arr.length()) {
            gkdPreset.add(readItem(arr.getJSONObject(i), true, "p-$i"))
        }
    }

    private fun loadGkdOverlay() {
        val f = File(app.filesDir, "gkd-overrides.json")
        if (!f.exists()) return
        val o = JSONObject(f.readText())
        gkdOn = o.optBoolean("presetOn", true)
        gkdDeleted.clear()
        val del = o.optJSONArray("deleted") ?: JSONArray()
        for (i in 0 until del.length()) gkdDeleted.add(del.getString(i))
        gkdDisabled.clear()
        val dis = o.optJSONArray("disabled") ?: JSONArray()
        for (i in 0 until dis.length()) gkdDisabled.add(dis.getString(i))
        gkdEdits.clear()
        val edits = o.optJSONObject("edits")
        if (edits != null) {
            val keys = edits.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val e = edits.getJSONObject(id)
                gkdEdits[id] = readItem(e, true, id).copy(id = id, enabled = e.optBoolean("enabled", true))
            }
        }
        gkdCustom.clear()
        val cus = o.optJSONArray("custom") ?: JSONArray()
        for (i in 0 until cus.length()) {
            val e = cus.getJSONObject(i)
            gkdCustom.add(readItem(e, false, "g-$i"))
        }
    }

    private fun persistGkd() {
        val o = JSONObject()
        o.put("presetOn", gkdOn)
        o.put("deleted", JSONArray(gkdDeleted.toList()))
        o.put("disabled", JSONArray(gkdDisabled.toList()))
        val edits = JSONObject()
        gkdEdits.forEach { (id, item) ->
            edits.put(
                id,
                JSONObject()
                    .put("pkg", item.pkg)
                    .put("app", item.app)
                    .put("group", item.group)
                    .put("match", item.match)
                    .put("enabled", item.enabled)
                    .put("activity", item.activity)
                    .put("action", item.action)
                    .put("delay", item.delay)
            )
        }
        o.put("edits", edits)
        val cus = JSONArray()
        gkdCustom.forEach { item ->
            cus.put(
                JSONObject()
                    .put("id", item.id)
                    .put("pkg", item.pkg)
                    .put("app", item.app)
                    .put("group", item.group)
                    .put("match", item.match)
                    .put("enabled", item.enabled)
                    .put("activity", item.activity)
                    .put("action", item.action)
                    .put("delay", item.delay)
            )
        }
        o.put("custom", cus)
        File(app.filesDir, "gkd-overrides.json").writeText(o.toString())
        bumpGkd()
    }

    private fun bumpGkd() {
        gkdGen += 1
        GkdGroups.invalidate()
    }
}
