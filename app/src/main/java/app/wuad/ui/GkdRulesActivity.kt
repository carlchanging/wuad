package app.wuad.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import app.wuad.core.GkdGroups
import app.wuad.core.RuleStore
import app.wuad.databinding.ActivityGkdRulesBinding
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class GkdRulesActivity : AppCompatActivity() {
    private lateinit var bind: ActivityGkdRulesBinding
    private val adapter = RuleAdapter()
    private val ui = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private val paintGen = AtomicInteger(0)
    private var query = ""
    private var gkdPkg: String? = null
    private var gkdAppName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bind = ActivityGkdRulesBinding.inflate(layoutInflater)
        setContentView(bind.root)
        bind.list.layoutManager = LinearLayoutManager(this)
        bind.list.adapter = adapter
        bind.list.setHasFixedSize(true)
        bind.list.setItemViewCacheSize(16)
        bind.drill.setOnClickListener {
            gkdPkg = null
            gkdAppName = ""
            bind.search.setText("")
            query = ""
            schedulePaint()
        }
        bind.search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim().orEmpty()
                ui.removeCallbacks(paintTask)
                ui.postDelayed(paintTask, 160)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
        bind.btnAdd.setOnClickListener {
            val i = Intent(this, RuleEditActivity::class.java).putExtra("kind", "gkd")
            if (!gkdPkg.isNullOrBlank()) {
                i.putExtra("pkg", gkdPkg)
                i.putExtra("app", gkdAppName)
            }
            startActivity(i)
        }
        adapter.onEdit = { startActivity(Intent(this, RuleEditActivity::class.java).putExtras(it)) }
        adapter.onDelete = { _, id ->
            RuleStore.deleteGkd(id)
            schedulePaint()
        }
        adapter.onToggle = { _, id, on, folder ->
            if (folder) RuleStore.setGkdAppEnabled(id, on) else RuleStore.setGkdEnabled(id, on)
            schedulePaint()
        }
        adapter.onOpen = { pkg, name ->
            gkdPkg = pkg
            gkdAppName = name
            bind.search.setText("")
            query = ""
            schedulePaint()
        }
    }

    override fun onResume() {
        super.onResume()
        schedulePaint()
    }

    override fun onDestroy() {
        ui.removeCallbacks(paintTask)
        exec.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!gkdPkg.isNullOrBlank()) {
            gkdPkg = null
            gkdAppName = ""
            bind.search.setText("")
            query = ""
            schedulePaint()
            return
        }
        super.onBackPressed()
    }

    private val paintTask = Runnable { paintNow() }

    private fun schedulePaint() {
        ui.removeCallbacks(paintTask)
        ui.post(paintTask)
    }

    private fun paintNow() {
        val q = query
        val drilled = gkdPkg
        val gen = paintGen.incrementAndGet()
        exec.execute {
            try {
                RuleStore.init(this)
                val visible = RuleStore.gkdVisible()
                val apps = GkdGroups.of(visible, if (drilled.isNullOrBlank()) q else "")
                val rows: List<RuleRow>
                val title: String
                val hint: String
                val drillText: String?
                val searchHint: String
                if (drilled.isNullOrBlank()) {
                    title = "规则"
                    hint = "预置 ${RuleStore.gkdPresetCount()} 条 · ${apps.size} 个应用。分类在 GKD 页开，默认只开开屏广告。先点应用再关单条。"
                    drillText = null
                    searchHint = "搜索应用名 / 包名"
                    rows = apps.map { a ->
                        RuleRow(
                            "gkd",
                            a.pkg,
                            a.name,
                            "${a.pkg} · ${a.total} 组 · ${a.onCount} 开" + if (a.splashOn) " · 开屏开着" else "",
                            a.onCount > 0,
                            true,
                            true,
                            a.name
                        )
                    }
                } else {
                    val app = apps.find { it.pkg == drilled }
                    val items = app?.items?.let { list ->
                        if (q.isEmpty()) list else list.filter {
                            it.group.lowercase().contains(q.lowercase()) || it.match.lowercase().contains(q.lowercase())
                        }
                    } ?: emptyList()
                    if (items.isEmpty() && q.isEmpty() && app == null) {
                        ui.post {
                            if (gen != paintGen.get() || isDestroyed) return@post
                            gkdPkg = null
                            gkdAppName = ""
                            paintNow()
                        }
                        return@execute
                    }
                    val name = app?.name?.ifBlank { drilled } ?: drilled
                    title = name
                    hint = "${items.size} 个规则组"
                    drillText = "← 返回应用列表"
                    searchHint = "搜索该应用的规则组"
                    rows = items.map {
                        RuleRow(
                            "gkd",
                            it.id,
                            it.group,
                            "匹配 " + it.match.take(48) + if (it.preset) " · 预置" else " · 自定义",
                            it.enabled,
                            it.preset
                        )
                    }
                }
                ui.post {
                    if (gen != paintGen.get() || isDestroyed) return@post
                    if (!drilled.isNullOrBlank()) gkdAppName = title
                    bind.pageTitle.text = title
                    bind.pageHint.text = hint
                    bind.search.hint = searchHint
                    if (drillText == null) {
                        bind.drill.visibility = View.GONE
                    } else {
                        bind.drill.visibility = View.VISIBLE
                        bind.drill.text = drillText
                    }
                    adapter.submit(rows)
                }
            } catch (e: Exception) {
                ui.post {
                    if (isDestroyed) return@post
                    Toast.makeText(this, "规则加载失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
