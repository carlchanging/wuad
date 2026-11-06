package app.wuad.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.wuad.core.DnsProbe
import app.wuad.core.EventStore
import app.wuad.core.WuadEvent
import app.wuad.databinding.ActivityLogsBinding
import app.wuad.databinding.ItemLogBinding

class LogsActivity : AppCompatActivity() {
    private lateinit var bind: ActivityLogsBinding
    private var filter = "all"
    private val pulse = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            paint()
            pulse.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bind = ActivityLogsBinding.inflate(layoutInflater)
        setContentView(bind.root)
        bind.list.layoutManager = LinearLayoutManager(this)
        bind.list.adapter = LogAdapter()
        bind.filter.setOnCheckedChangeListener { _, id ->
            filter = when (id) {
                bind.fDns.id -> "dns"
                bind.fClick.id -> "click"
                bind.fConn.id -> "conn"
                else -> "all"
            }
            paint()
        }
        when (intent.getStringExtra("filter")) {
            "dns" -> bind.fDns.isChecked = true
            "click" -> bind.fClick.isChecked = true
            "conn" -> bind.fConn.isChecked = true
        }
        bind.btnProbe.setOnClickListener {
            bind.btnProbe.isEnabled = false
            Thread {
                val msg = try {
                    DnsProbe.run(this)
                } catch (e: Exception) {
                    e.message ?: "诊断失败"
                }
                Handler(Looper.getMainLooper()).post {
                    bind.btnProbe.isEnabled = true
                    paint()
                    Toast.makeText(this, msg.take(80), Toast.LENGTH_LONG).show()
                }
            }.start()
        }
        bind.btnClear.setOnClickListener {
            EventStore.clear()
            paint()
        }
    }

    override fun onResume() {
        super.onResume()
        paint()
        pulse.postDelayed(tick, 1000)
    }

    override fun onPause() {
        pulse.removeCallbacks(tick)
        super.onPause()
    }

    private fun paint() {
        EventStore.refreshFromDisk()
        val rows = EventStore.all().filter {
            when (filter) {
                "all" -> true
                "click" -> EventStore.inClickTab(it) && it.t >= EventStore.dayStart()
                else -> it.kind == filter
            }
        }
        (bind.list.adapter as LogAdapter).submit(rows)
        bind.hint.text = when (filter) {
            "dns" -> if (rows.isEmpty()) {
                "这才是广告拦截。要出现「REJECT 域名」。启动/隧道在「全部」里。"
            } else {
                val top = EventStore.todayTop("dns", 5).joinToString(" · ") { "${it.first} ${it.second}" }
                "今日拦截 ${EventStore.today("dns")}。标题前面是应用名。Top：$top"
            }
            "click" -> if (rows.isEmpty()) {
                "今日还没有跳过。点上开屏会出现「点击 淘宝」，跟首页同一条数。"
            } else {
                "今日跳过 ${rows.size}，跟首页同一口径。"
            }
            "conn" -> if (rows.isEmpty()) {
                "没有连接记录：请求多半死在解析。浏览器 ERR_NAME_NOT_RESOLVED 就是这个。点「诊断解析」。"
            } else {
                "连接 ${rows.size} 条。标题最前面是应用名，内核自己的请求标成「内核」。"
            }
            else -> "SYS 启动，DNS 拦截/失败，连接才是放行。解析失败不会出现连接。点「诊断解析」。"
        }
    }

    private class LogAdapter : RecyclerView.Adapter<LogAdapter.VH>() {
        private var rows: List<WuadEvent> = emptyList()
        fun submit(v: List<WuadEvent>) {
            rows = v
            notifyDataSetChanged()
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }
        override fun onBindViewHolder(holder: VH, position: Int) {
            val e = rows[position]
            holder.b.tag.text = e.kind.uppercase() + " · " + EventStore.clock(e.t)
            holder.b.title.text = e.title
            holder.b.sub.text = e.sub
        }
        override fun getItemCount() = rows.size
        class VH(val b: ItemLogBinding) : RecyclerView.ViewHolder(b.root)
    }
}
