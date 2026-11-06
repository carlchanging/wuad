package app.wuad.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.wuad.core.Blocklist
import app.wuad.core.DnsSubStore
import app.wuad.core.GkdGroups
import app.wuad.core.Prefs
import app.wuad.core.RemoteFiles
import app.wuad.core.RouteStore
import app.wuad.core.RouteUpdater
import app.wuad.core.RuleStore
import app.wuad.core.RuleUpdater
import app.wuad.databinding.ActivityRulesBinding
import app.wuad.databinding.ItemRuleBinding
import app.wuad.vpn.WuadVpnService

class RulesActivity : AppCompatActivity() {
    private lateinit var bind: ActivityRulesBinding
    private val adapter = RuleAdapter()
    private var tab = "dns"
    private var query = ""
    private var gkdPkg: String? = null
    private var gkdAppName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bind = ActivityRulesBinding.inflate(layoutInflater)
        setContentView(bind.root)
        RuleStore.init(this)
        RouteStore.init(this)
        bind.list.layoutManager = LinearLayoutManager(this)
        bind.list.adapter = adapter
        bind.tabs.setOnCheckedChangeListener { _, id ->
            tab = when (id) {
                bind.tabGkd.id -> "gkd"
                bind.tabRoute.id -> "route"
                else -> "dns"
            }
            if (tab != "gkd") gkdPkg = null
            paint()
        }
        bind.drill.setOnClickListener {
            gkdPkg = null
            gkdAppName = ""
            paint()
        }
        bind.search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim().orEmpty()
                paint()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
        bind.btnAdd.setOnClickListener {
            val i = Intent(this, RuleEditActivity::class.java).putExtra("kind", tab)
            if (tab == "gkd" && !gkdPkg.isNullOrBlank()) {
                i.putExtra("pkg", gkdPkg)
                i.putExtra("app", gkdAppName)
            }
            startActivity(i)
        }
        bind.btnPreset.setOnClickListener {
            when (tab) {
                "dns" -> if (RuleStore.dnsPresetOn()) RuleStore.removeAntiAdPreset() else RuleStore.enableAntiAd(true)
                "gkd" -> if (RuleStore.gkdPresetOn()) RuleStore.removeGkdPreset() else RuleStore.enableGkd(true)
                else -> if (RouteStore.presetsOn()) RouteStore.disablePresets() else RouteStore.enablePresets()
            }
            Toast.makeText(this, "预置状态已改", Toast.LENGTH_SHORT).show()
            paint()
            bumpRoute()
        }
        bind.btnRestore.setOnClickListener {
            when (tab) {
                "dns" -> RuleStore.restoreAntiAdPreset()
                "gkd" -> RuleStore.restoreGkdPreset()
                else -> RouteStore.restorePresets()
            }
            Toast.makeText(this, "预置已恢复", Toast.LENGTH_SHORT).show()
            paint()
            bumpRoute()
        }
        adapter.onEdit = { startActivity(Intent(this, RuleEditActivity::class.java).putExtras(it)) }
        adapter.onDelete = { kind, id ->
            when (kind) {
                "dns" -> RuleStore.deleteDns(id)
                "gkd" -> RuleStore.deleteGkd(id)
                else -> RouteStore.delete(id)
            }
            paint()
            if (kind == "route") bumpRoute()
        }
        adapter.onToggle = { kind, id, on, folder ->
            when {
                kind == "gkd" && folder -> RuleStore.setGkdAppEnabled(id, on)
                kind == "gkd" -> RuleStore.setGkdEnabled(id, on)
                else -> RouteStore.setEnabled(id, on)
            }
            paint()
            if (kind == "route") bumpRoute()
        }
        adapter.onOpen = { pkg, name ->
            gkdPkg = pkg
            gkdAppName = name
            paint()
        }
        bind.btnSaveUrl.setOnClickListener {
            val u = bind.url.text.toString().trim()
            when (tab) {
                "dns" -> Toast.makeText(this, "去 DNS 页「过滤订阅」里改", Toast.LENGTH_SHORT).show()
                "gkd" -> Prefs.gkdUrl = u
                else -> Toast.makeText(this, "GEOSITE/GEOIP 地址写在每条规则里", Toast.LENGTH_SHORT).show()
            }
            if (tab == "gkd") Toast.makeText(this, "地址已保存", Toast.LENGTH_SHORT).show()
        }
        bind.btnUpdate.setOnClickListener {
            val url = bind.url.text.toString().trim()
            if (tab == "gkd") Prefs.gkdUrl = url
            bind.btnUpdate.isEnabled = false
            bind.btnUpdate.text = "更新中…"
            Thread {
                val msg = try {
                    when (tab) {
                        "dns" -> RuleUpdater.updateAntiAd(this)
                        "gkd" -> RuleUpdater.updateGkd(this)
                        else -> RouteUpdater.updateEnabled(this)
                    }
                } catch (e: Exception) {
                    "更新失败：${e.message}"
                }
                Handler(Looper.getMainLooper()).post {
                    bind.btnUpdate.isEnabled = true
                    bind.btnUpdate.text = "更新规则"
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    paint()
                    bumpRoute()
                }
            }.start()
        }
    }

    override fun onResume() {
        super.onResume()
        paint()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (tab == "gkd" && !gkdPkg.isNullOrBlank()) {
            gkdPkg = null
            gkdAppName = ""
            paint()
            return
        }
        super.onBackPressed()
    }

    private fun paint() {
        val dnsOn = RuleStore.dnsPresetOn()
        val gkdOn = RuleStore.gkdPresetOn()
        if (tab != "gkd") bind.drill.visibility = View.GONE
        if (tab == "dns") {
            bind.search.hint = "搜索规则"
            bind.url.visibility = View.GONE
            bind.btnSaveUrl.visibility = View.GONE
            val n = try { DnsSubStore.enabled().size } catch (_: Exception) { 0 }
            bind.presetLine.text =
                "已启用 $n 份订阅，合并 ${Blocklist.size.get()} 条" + (if (dnsOn) " · 预置开" else " · 预置停") +
                    "。上次更新 ${RemoteFiles.fmtTime(Prefs.lastUpdate("anti"))}。点更新拉全部启用的订阅。"
            bind.btnPreset.text = if (dnsOn) "停用预置" else "启用预置"
            val rows = ArrayList<Row>()
            RuleStore.dnsCustom().filter { query.isEmpty() || it.host.contains(query.lowercase()) }.forEach {
                rows.add(Row("dns", it.id, it.host, (if (it.action == "allow") "放行 · 自定义" else "拦截 · 自定义"), it.action != "allow", false))
            }
            if (dnsOn && query.length >= 2) {
                Blocklist.searchPreset(query).forEach { host ->
                    if (rows.none { it.id == host }) {
                        rows.add(Row("dns", host, host, "拦截 · anti-AD 预置", true, true))
                    }
                }
            }
            adapter.submit(rows)
        } else if (tab == "gkd") {
            bind.url.hint = "预置订阅地址"
            bind.url.setText(Prefs.gkdUrl)
            val apps = GkdGroups.of(RuleStore.gkdVisible())
            bind.presetLine.text =
                "预置 GKD ${RuleStore.gkdPresetCount()} 条，收成 ${apps.size} 个应用" +
                    (if (gkdOn) " · 已启用" else " · 已停用") +
                    "。上次更新 ${RemoteFiles.fmtTime(Prefs.lastUpdate("gkd"))}。分类在 GKD 页开，默认只开开屏广告。"
            bind.btnPreset.text = if (gkdOn) "停用预置" else "启用预置"
            val drilled = gkdPkg
            if (drilled.isNullOrBlank()) {
                bind.drill.visibility = View.GONE
                bind.search.hint = "搜索应用名 / 包名"
                val rows = GkdGroups.of(RuleStore.gkdVisible(), query).map { a ->
                    val splash = if (a.splashOn) " · 开屏开着" else ""
                    Row(
                        "gkd",
                        a.pkg,
                        a.name,
                        "${a.pkg} · ${a.total} 组 · ${a.onCount} 开$splash",
                        a.onCount > 0,
                        true,
                        folder = true,
                        name = a.name
                    )
                }
                adapter.submit(rows)
            } else {
                val items = GkdGroups.ofPkg(RuleStore.gkdVisible(), drilled, query)
                if (items.isEmpty() && query.isEmpty()) {
                    gkdPkg = null
                    gkdAppName = ""
                    paint()
                    return
                }
                gkdAppName = items.firstOrNull()?.app?.ifBlank { drilled } ?: drilled
                bind.drill.visibility = View.VISIBLE
                bind.drill.text = "← $gkdAppName · ${items.size} 组"
                bind.search.hint = "搜索该应用的规则组"
                adapter.submit(
                    items.map {
                        Row(
                            "gkd",
                            it.id,
                            it.group,
                            "匹配 " + it.match.take(48) + (if (it.preset) " · 预置" else " · 自定义"),
                            it.enabled,
                            it.preset
                        )
                    }
                )
            }
        } else {
            bind.search.hint = "搜索规则"
            bind.url.setText("")
            bind.url.hint = "GEOSITE/GEOIP 地址写在每条规则里"
            bind.presetLine.text =
                "gfw/cn 域名优先，GEOIP 兜底。geosite/geoip 用 MetaCubeX 那份，点更新规则会下载。上次 ${RemoteFiles.fmtTime(Prefs.lastUpdate("route"))}"
            bind.btnPreset.text = if (RouteStore.presetsOn()) "停用预置" else "启用预置"
            val q = query.lowercase()
            val rows = RouteStore.all()
                .filter {
                    q.isEmpty() || it.payload.lowercase().contains(q) || it.type.lowercase().contains(q) ||
                        it.policy.lowercase().contains(q) || it.url.lowercase().contains(q)
                }
                .map { r ->
                    val extra = if (r.type == "PROVIDER") {
                        (if (r.count > 0) "${r.count} 条 · " else "") + r.url
                    } else if (r.url.isNotBlank()) {
                        "${r.type},${r.payload} · ${r.url}"
                    } else {
                        "${r.type},${r.payload},${r.policy}"
                    }
                    Row(
                        "route",
                        r.id,
                        "${r.payload} → ${r.policy}",
                        extra + (if (r.preset) " · 预置" else " · 自定义"),
                        r.enabled,
                        r.preset
                    )
                }
            adapter.submit(rows)
        }
    }

    private fun bumpRoute() {
        if (tab != "route") return
        WuadVpnService.reload(this)
    }

    data class Row(
        val kind: String,
        val id: String,
        val title: String,
        val sub: String,
        val on: Boolean,
        val preset: Boolean,
        val folder: Boolean = false,
        val name: String = ""
    )

    private class RuleAdapter : RecyclerView.Adapter<RuleAdapter.VH>() {
        private var rows: List<Row> = emptyList()
        var onEdit: (Bundle) -> Unit = {}
        var onDelete: (String, String) -> Unit = { _, _ -> }
        var onToggle: (String, String, Boolean, Boolean) -> Unit = { _, _, _, _ -> }
        var onOpen: (String, String) -> Unit = { _, _ -> }

        fun submit(v: List<Row>) {
            rows = v
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemRuleBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = rows[position]
            holder.b.title.text = if (row.folder) row.title + "  ›" else row.title
            holder.b.sub.text = row.sub
            holder.b.toggle.visibility = if (row.kind == "dns") View.GONE else View.VISIBLE
            holder.b.toggle.setOnCheckedChangeListener(null)
            holder.b.toggle.isChecked = row.on
            holder.b.toggle.setOnCheckedChangeListener { _, on -> onToggle(row.kind, row.id, on, row.folder) }
            holder.b.edit.visibility = if (row.folder) View.GONE else View.VISIBLE
            holder.b.delete.visibility = if (row.folder) View.GONE else View.VISIBLE
            holder.itemView.setOnClickListener {
                if (row.folder) onOpen(row.id, row.name.ifBlank { row.title })
            }
            holder.b.edit.setOnClickListener {
                val extras = Bundle()
                extras.putString("kind", row.kind)
                extras.putString("id", row.id)
                extras.putString("title", row.title)
                extras.putString("sub", row.sub)
                onEdit(extras)
            }
            holder.b.delete.setOnClickListener { onDelete(row.kind, row.id) }
        }

        override fun getItemCount() = rows.size
        class VH(val b: ItemRuleBinding) : RecyclerView.ViewHolder(b.root)
    }
}
