package app.wuad.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.wuad.core.Prefs
import app.wuad.databinding.ActivityAppsBinding
import app.wuad.databinding.ItemAppBinding
import app.wuad.vpn.WuadVpnService

class AppsActivity : AppCompatActivity() {
    data class Row(val name: String, val pkg: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bind = ActivityAppsBinding.inflate(layoutInflater)
        setContentView(bind.root)
        val mode = intent.getStringExtra("mode").orEmpty()
        val feature = mode in setOf("dns", "route", "gkd")
        bind.pageTitle.text = when (mode) {
            "dns" -> "DNS 不启用"
            "route" -> "分流 不启用"
            "gkd" -> "GKD 不启用"
            else -> "分应用"
        }
        bind.pageHint.text = when (mode) {
            "dns" -> "默认全部启用去广告。点右侧关掉某个 App，它就不再走 anti-AD。"
            "route" -> "默认全部走分流。点右侧关掉某个 App，它一律直连、不走代理规则。"
            "gkd" -> "默认全部启用跳过。点右侧关掉某个 App，引擎完全不碰它。"
            else -> "点右侧循环：跟随规则 / 代理 / 直连 / 排除。排除=不进隧道。改排除要重开网络栈。"
        }
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val rows = packageManager.queryIntentActivities(launch, PackageManager.MATCH_ALL)
            .map {
                val a = it.activityInfo
                Row(a.loadLabel(packageManager).toString(), a.packageName)
            }
            .distinctBy { it.pkg }
            .sortedWith(
                compareBy<Row> { if (feature && Prefs.featureOff(mode, it.pkg)) 0 else 1 }
                    .thenBy { it.name.lowercase() }
            )
        val adapter = AppAdapter(rows, mode) { restart ->
            if (feature) {
                if (mode != "gkd") WuadVpnService.reload(this)
                return@AppAdapter
            }
            if (restart) {
                if (WuadVpnService.running.get()) {
                    WuadVpnService.stop(this)
                    bind.root.postDelayed({ WuadVpnService.start(this) }, 400)
                    Toast.makeText(this, "排除名单变了，隧道在重开", Toast.LENGTH_SHORT).show()
                }
            } else {
                WuadVpnService.reload(this)
            }
        }
        bind.list.layoutManager = LinearLayoutManager(this)
        bind.list.adapter = adapter
        bind.search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim()?.lowercase().orEmpty()
                adapter.filter(q)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
    }

    private class AppAdapter(
        private val all: List<Row>,
        private val mode: String,
        private val onChange: (Boolean) -> Unit
    ) : RecyclerView.Adapter<AppAdapter.VH>() {
        private val feature = mode in setOf("dns", "route", "gkd")
        private var rows = all
        fun filter(q: String) {
            rows = if (q.isEmpty()) all else all.filter { it.name.lowercase().contains(q) || it.pkg.lowercase().contains(q) }
            notifyDataSetChanged()
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }
        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = rows[position]
            holder.b.name.text = row.name
            holder.b.pkg.text = row.pkg
            fun paint() {
                holder.b.policy.text = if (feature) {
                    if (Prefs.featureOff(mode, row.pkg)) "不启用" else "启用"
                } else when (Prefs.appPolicy(row.pkg)) {
                    "proxy" -> "代理"
                    "direct" -> "直连"
                    "exclude" -> "排除"
                    else -> "跟随"
                }
            }
            paint()
            holder.b.policy.setOnClickListener {
                if (feature) {
                    Prefs.setFeatureOff(mode, row.pkg, !Prefs.featureOff(mode, row.pkg))
                    paint()
                    onChange(false)
                    return@setOnClickListener
                }
                val cur = Prefs.appPolicy(row.pkg)
                val next = when (cur) {
                    "default" -> "proxy"
                    "proxy" -> "direct"
                    "direct" -> "exclude"
                    else -> "default"
                }
                val wasEx = cur == "exclude"
                Prefs.setAppPolicy(row.pkg, next)
                paint()
                onChange(wasEx || next == "exclude")
            }
        }
        override fun getItemCount() = rows.size
        class VH(val b: ItemAppBinding) : RecyclerView.ViewHolder(b.root)
    }
}
