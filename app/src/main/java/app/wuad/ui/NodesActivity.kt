package app.wuad.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.wuad.core.MihomoApi
import app.wuad.core.MihomoEngine
import app.wuad.core.Prefs
import app.wuad.core.ProxyNode
import app.wuad.core.RouteToggle
import app.wuad.databinding.ActivityNodesBinding
import app.wuad.databinding.ItemNodeBinding
import app.wuad.vpn.WuadVpnService

class NodesActivity : AppCompatActivity() {
    private lateinit var bind: ActivityNodesBinding
    private val adapter = NodeAdapter()
    private var painting = false
    private val vpnPermit = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) {
            Prefs.vpnWanted = true
            WuadVpnService.start(this)
        } else {
            Prefs.vpnWanted = false
            if (!WuadVpnService.running.get()) Prefs.routeOn = false
        }
        paint()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bind = ActivityNodesBinding.inflate(layoutInflater)
        setContentView(bind.root)
        app.wuad.core.SubStore.init(this)
        bind.list.layoutManager = LinearLayoutManager(this)
        bind.list.adapter = adapter
        adapter.onPick = { name ->
            Thread {
                val msg = try {
                    MihomoApi.select(name)
                    if (MihomoEngine.running) "已切到 $name" else "已记下 $name，开分流后走它"
                } catch (e: Exception) {
                    "切换失败：${e.message}"
                }
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    paint()
                }
            }.start()
        }
        bind.swRoute.setOnCheckedChangeListener { _, on ->
            if (painting) return@setOnCheckedChangeListener
            val prep = RouteToggle.apply(this, on)
            if (prep != null) vpnPermit.launch(prep)
            Toast.makeText(this, if (on) "分流开" else "分流关", Toast.LENGTH_SHORT).show()
            paint()
            bind.root.postDelayed({ paint() }, 800)
            bind.root.postDelayed({ paint() }, 1800)
        }
        bind.modes.setOnCheckedChangeListener { _, id ->
            if (painting) return@setOnCheckedChangeListener
            val mode = when (id) {
                bind.modeGlobal.id -> "global"
                bind.modeDirect.id -> "direct"
                else -> "rule"
            }
            Prefs.mode = mode
            Thread {
                try { if (MihomoEngine.running) MihomoApi.setMode(mode) } catch (_: Exception) {}
                Handler(Looper.getMainLooper()).post { paint() }
            }.start()
        }
        bind.btnDelay.setOnClickListener {
            if (!MihomoEngine.running) {
                Toast.makeText(this, "分流没开，测不了延迟", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            bind.btnDelay.isEnabled = false
            Thread {
                val rows = adapter.snapshot()
                val map = try {
                    MihomoApi.delayGroup("PROXY")
                } catch (_: Exception) {
                    emptyMap()
                }
                rows.forEach { n ->
                    if (groupType(n.type)) return@forEach
                    n.delay = when {
                        map.containsKey(n.name) -> map.getValue(n.name)
                        else -> try { MihomoApi.delay(n.name) } catch (_: Exception) { -1 }
                    }
                }
                Handler(Looper.getMainLooper()).post {
                    adapter.submit(rows)
                    bind.btnDelay.isEnabled = true
                    Toast.makeText(this, "测速结束", Toast.LENGTH_SHORT).show()
                }
            }.start()
        }
        bind.btnRefresh.setOnClickListener {
            if (Prefs.subUrl.isBlank() && app.wuad.core.SubStore.all().isEmpty()) {
                Toast.makeText(this, "先去设置里填机场订阅", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Thread {
                val msg = try {
                    MihomoApi.updateSub()
                } catch (e: Exception) {
                    "刷新失败：${e.message}"
                }
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    paint()
                }
            }.start()
        }
    }

    override fun onResume() {
        super.onResume()
        paint()
    }

    private fun paint() {
        painting = true
        bind.swRoute.isChecked = Prefs.routeOn
        when (Prefs.mode) {
            "global" -> bind.modeGlobal.isChecked = true
            "direct" -> bind.modeDirect.isChecked = true
            else -> bind.modeRule.isChecked = true
        }
        bind.hint.text = when {
            Prefs.subUrl.isBlank() && app.wuad.core.SubStore.all().isEmpty() ->
                "没填机场。PROXY 现在等于直连，测速也没意义。"
            !Prefs.routeOn || !MihomoEngine.running ->
                "分流关着也能看、能换节点。开分流后才会真正走。"
            else -> "点一行换节点。日常用规则；全局会全部走当前节点（含国内）。两个模式共用你选的节点。"
        }
        Thread {
            val rows = try {
                MihomoApi.visibleNodes().second
            } catch (_: Exception) {
                emptyList()
            }
            Handler(Looper.getMainLooper()).post {
                adapter.submit(rows)
                val real = rows.count { it.name != "AUTO" && it.name != "DIRECT" }
                if (real == 0 && app.wuad.core.SubStore.all().isNotEmpty()) {
                    bind.hint.text = "订阅在，本地还没节点。点更新订阅。"
                }
                painting = false
            }
        }.start()
    }

    private class NodeAdapter : RecyclerView.Adapter<NodeAdapter.VH>() {
        private var rows: List<ProxyNode> = emptyList()
        var onPick: (String) -> Unit = {}
        fun snapshot() = rows
        fun submit(v: List<ProxyNode>) {
            rows = v
            notifyDataSetChanged()
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(ItemNodeBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
        override fun onBindViewHolder(holder: VH, position: Int) {
            val n = rows[position]
            holder.b.name.text = n.name + if (n.now) "  · 当前" else ""
            holder.b.meta.text = n.type
            holder.b.delay.text = when {
                n.delay > 0 -> "${n.delay} ms"
                n.delay < 0 -> "超时"
                else -> ""
            }
            holder.itemView.setOnClickListener { onPick(n.name) }
        }
        override fun getItemCount() = rows.size
        class VH(val b: ItemNodeBinding) : RecyclerView.ViewHolder(b.root)
    }

    companion object {
        fun groupType(type: String): Boolean =
            type.equals("Selector", true) || type.equals("URLTest", true) ||
                type.equals("Fallback", true) || type.equals("LoadBalance", true)
    }
}
