package app.wuad.ui

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import app.wuad.core.Blocklist
import app.wuad.core.DnsSubStore
import app.wuad.core.GkdGroups
import app.wuad.core.Prefs
import app.wuad.core.RemoteFiles
import app.wuad.core.RouteStore
import app.wuad.core.RouteUpdater
import app.wuad.core.RuleStore
import app.wuad.core.RuleUpdater
import app.wuad.databinding.IncludeRulesBinding
import app.wuad.vpn.WuadVpnService

class RulesPage(
    private val host: Fragment,
    private val r: IncludeRulesBinding,
    private val kind: String
) {
    private val adapter = RuleAdapter()
    private var query = ""
    var gkdPkg: String? = null
    var gkdAppName = ""

    fun attach() {
        val ctx = host.requireContext()
        RuleStore.init(ctx)
        RouteStore.init(ctx)
        r.list.layoutManager = LinearLayoutManager(ctx)
        r.list.adapter = adapter
        r.list.setHasFixedSize(false)
        r.list.setItemViewCacheSize(if (kind == "gkd") 24 else 8)
        r.list.isNestedScrollingEnabled = false
        (r.list.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let { lp ->
            lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            lp.weight = 0f
            r.list.layoutParams = lp
        }
        r.search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim().orEmpty()
                paint()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
        r.btnAdd.setOnClickListener {
            val i = Intent(ctx, RuleEditActivity::class.java).putExtra("kind", kind)
            if (kind == "gkd" && !gkdPkg.isNullOrBlank()) {
                i.putExtra("pkg", gkdPkg)
                i.putExtra("app", gkdAppName)
            }
            host.startActivity(i)
        }
        r.btnPreset.setOnClickListener {
            when (kind) {
                "dns" -> if (RuleStore.dnsPresetOn()) RuleStore.removeAntiAdPreset() else RuleStore.enableAntiAd(true)
                "gkd" -> if (RuleStore.gkdPresetOn()) RuleStore.removeGkdPreset() else RuleStore.enableGkd(true)
                else -> if (RouteStore.presetsOn()) RouteStore.disablePresets() else RouteStore.enablePresets()
            }
            toast("预置状态已改")
            paint()
            bumpRoute()
        }
        r.btnRestore.setOnClickListener {
            when (kind) {
                "dns" -> RuleStore.restoreAntiAdPreset()
                "gkd" -> RuleStore.restoreGkdPreset()
                else -> RouteStore.restorePresets()
            }
            toast("预置已恢复")
            paint()
            bumpRoute()
        }
        r.drill.setOnClickListener {
            gkdPkg = null
            gkdAppName = ""
            paint()
        }
        adapter.onEdit = { host.startActivity(Intent(ctx, RuleEditActivity::class.java).putExtras(it)) }
        adapter.onDelete = { k, id ->
            when (k) {
                "dns" -> RuleStore.deleteDns(id)
                "gkd" -> RuleStore.deleteGkd(id)
                else -> RouteStore.delete(id)
            }
            paint()
            if (k == "route") bumpRoute()
        }
        adapter.onToggle = { k, id, on, folder ->
            when {
                k == "gkd" && folder -> RuleStore.setGkdAppEnabled(id, on)
                k == "gkd" -> RuleStore.setGkdEnabled(id, on)
                else -> RouteStore.setEnabled(id, on)
            }
            paint()
            if (k == "route") bumpRoute()
        }
        adapter.onOpen = { pkg, name ->
            gkdPkg = pkg
            gkdAppName = name
            paint()
        }
        r.btnSaveUrl.setOnClickListener {
            val u = r.url.text.toString().trim()
            when (kind) {
                "dns" -> toast("DNS 订阅在上面「过滤订阅」里改")
                "gkd" -> Prefs.gkdUrl = u
                else -> toast("GEOSITE/GEOIP 地址写在每条规则里")
            }
            if (kind == "gkd") toast("地址已保存")
        }
        r.btnUpdate.setOnClickListener {
            val url = r.url.text.toString().trim()
            if (kind == "gkd") Prefs.gkdUrl = url
            r.btnUpdate.isEnabled = false
            r.btnUpdate.text = "更新中…"
            Thread {
                val msg = try {
                    when (kind) {
                        "dns" -> RuleUpdater.updateAntiAd(ctx)
                        "gkd" -> RuleUpdater.updateGkd(ctx)
                        else -> RouteUpdater.updateEnabled(ctx)
                    }
                } catch (e: Exception) {
                    "更新失败：${e.message}"
                }
                Handler(Looper.getMainLooper()).post {
                    r.btnUpdate.isEnabled = true
                    r.btnUpdate.text = "更新规则"
                    toast(msg)
                    paint()
                    bumpRoute()
                }
            }.start()
        }
    }

    fun paint() {
        if (kind == "dns") paintDns()
        else if (kind == "gkd") paintGkd()
        else paintRoute()
    }

    fun onBack(): Boolean {
        if (kind == "gkd" && !gkdPkg.isNullOrBlank()) {
            gkdPkg = null
            gkdAppName = ""
            paint()
            return true
        }
        return false
    }

    private fun paintDns() {
        val on = RuleStore.dnsPresetOn()
        r.drill.visibility = View.GONE
        r.search.hint = "搜索域名"
        r.url.visibility = View.GONE
        r.btnSaveUrl.visibility = View.GONE
        val n = try { DnsSubStore.enabled().size } catch (_: Exception) { 0 }
        r.presetLine.text = "已启用 $n 份订阅，合并 ${Blocklist.size.get()} 条" + (if (on) " · 预置开" else " · 预置停") +
            "。上次 ${RemoteFiles.fmtTime(Prefs.lastUpdate("anti"))}。点更新会拉全部启用的订阅。"
        r.btnPreset.text = if (on) "停用预置" else "启用预置"
        val rows = ArrayList<RuleRow>()
        RuleStore.dnsCustom().filter { query.isEmpty() || it.host.contains(query.lowercase()) }.forEach {
            rows.add(RuleRow("dns", it.id, it.host, if (it.action == "allow") "放行 · 自定义" else "拦截 · 自定义", it.action != "allow", false))
        }
        if (on && query.length >= 2) {
            Blocklist.searchPreset(query).forEach { host ->
                if (rows.none { it.id == host }) rows.add(RuleRow("dns", host, host, "拦截 · anti-AD 预置", true, true))
            }
        }
        adapter.submit(rows)
    }

    private fun paintGkd() {
        val on = RuleStore.gkdPresetOn()
        val apps = GkdGroups.of(RuleStore.gkdVisible(), query)
        r.url.hint = "GKD 订阅地址"
        if (r.url.text?.toString() != Prefs.gkdUrl) r.url.setText(Prefs.gkdUrl)
        r.presetLine.text = "预置 GKD ${RuleStore.gkdPresetCount()} 条 · ${apps.size} 个应用" +
            (if (on) " · 已启用" else " · 已停用") + "。上次 ${RemoteFiles.fmtTime(Prefs.lastUpdate("gkd"))}"
        r.btnPreset.text = if (on) "停用预置" else "启用预置"
        val drilled = gkdPkg
        if (drilled.isNullOrBlank()) {
            r.drill.visibility = View.GONE
            r.search.hint = "搜索应用名 / 包名"
            adapter.submit(
                apps.map { a ->
                    RuleRow("gkd", a.pkg, a.name, "${a.pkg} · ${a.total} 组 · ${a.onCount} 开" + if (a.splashOn) " · 开屏开着" else "", a.onCount > 0, true, true, a.name)
                }
            )
        } else {
            val app = apps.find { it.pkg == drilled }
            val items = app?.items ?: emptyList()
            if (items.isEmpty() && query.isEmpty()) {
                gkdPkg = null
                gkdAppName = ""
                paint()
                return
            }
            gkdAppName = app?.name?.ifBlank { drilled } ?: drilled
            r.drill.visibility = View.VISIBLE
            r.drill.text = "← $gkdAppName · ${items.size} 组"
            r.search.hint = "搜索该应用的规则组"
            adapter.submit(
                items.map {
                    RuleRow("gkd", it.id, it.group, "匹配 " + it.match.take(48) + if (it.preset) " · 预置" else " · 自定义", it.enabled, it.preset)
                }
            )
        }
    }

    private fun paintRoute() {
        r.drill.visibility = View.GONE
        r.search.hint = "搜索分流规则"
        r.url.setText("")
        r.url.hint = "GEOSITE/GEOIP 地址写在每条规则里"
        r.presetLine.text = "gfw/cn 域名优先，GEOIP 兜底。geosite/geoip 用 MetaCubeX 那份，点更新规则会下载。上次 ${RemoteFiles.fmtTime(Prefs.lastUpdate("route"))}"
        r.btnPreset.text = if (RouteStore.presetsOn()) "停用预置" else "启用预置"
        val q = query.lowercase()
        adapter.submit(
            RouteStore.all().filter {
                q.isEmpty() || it.payload.lowercase().contains(q) || it.type.lowercase().contains(q) ||
                    it.policy.lowercase().contains(q) || it.url.lowercase().contains(q)
            }.map { rule ->
                val extra = if (rule.type == "PROVIDER") {
                    (if (rule.count > 0) "${rule.count} 条 · " else "") + rule.url
                } else if (rule.url.isNotBlank()) {
                    "${rule.type},${rule.payload} · ${rule.url}"
                } else {
                    "${rule.type},${rule.payload},${rule.policy}"
                }
                RuleRow("route", rule.id, "${rule.payload} → ${rule.policy}", extra + if (rule.preset) " · 预置" else " · 自定义", rule.enabled, rule.preset)
            }
        )
    }

    private fun toast(s: String) = Toast.makeText(host.requireContext(), s, Toast.LENGTH_SHORT).show()

    private fun bumpRoute() {
        if (kind != "route") return
        WuadVpnService.reload(host.requireContext())
    }
}
