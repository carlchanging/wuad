package app.wuad.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

data class WuadEvent(
    val t: Long,
    val kind: String,
    val title: String,
    val sub: String
)

object EventStore {
    private val lock = Any()
    private val items = ArrayDeque<WuadEvent>()
    private val ready = AtomicBoolean(false)
    private lateinit var app: Context
    private val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val clockFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var persistDirty = 0

    fun init(ctx: Context) {
        app = ctx.applicationContext
        if (ready.get()) return
        synchronized(lock) {
            if (ready.get()) return
            items.clear()
            loadLocked("wuad-log", "items")
            loadLocked("wuad-log", "pins")
            items.sortByDescending { it.t }
            ready.set(true)
        }
    }

    fun ensure(ctx: Context) {
        if (!ready.get()) init(ctx)
        else app = ctx.applicationContext
    }

    fun add(kind: String, title: String, sub: String) {
        if (!ready.get() && !::app.isInitialized) return
        val ev = WuadEvent(System.currentTimeMillis(), kind, title, sub)
        synchronized(lock) {
            val dup = items.any { it.kind == ev.kind && it.title == ev.title && ev.t - it.t < 800 }
            if (!dup) {
                items.addFirst(ev)
                while (items.size > 400) items.removeLast()
                persistLocked(kind == "click" || kind == "sys")
            }
            if (kind == "click" && !title.startsWith("点击")) return
            if (kind == "click") {
                if (!dup) syncClickStat()
            } else if (kind == "dns" || !dup) {
                bump(kind, false)
            }
        }
    }

    fun flush() {
        if (!ready.get() || !::app.isInitialized) return
        synchronized(lock) { persistLocked(true) }
    }

    fun all(): List<WuadEvent> = synchronized(lock) { items.toList() }

    fun refreshFromDisk() {
        if (!::app.isInitialized) return
        synchronized(lock) {
            loadLocked("wuad-log", "items")
            loadLocked("wuad-log", "pins")
            val sorted = items.sortedByDescending { it.t }
            items.clear()
            items.addAll(sorted)
            while (items.size > 400) items.removeLast()
            ready.set(true)
        }
    }

    fun isClick(e: WuadEvent): Boolean =
        e.kind == "click" && e.title.startsWith("点击")

    fun inClickTab(e: WuadEvent): Boolean = isClick(e)

    fun todayClicks(): List<WuadEvent> {
        val since = dayStart()
        return synchronized(lock) { items.filter { isClick(it) && it.t >= since } }
    }

    fun clear() {
        synchronized(lock) {
            items.clear()
            persistLocked(true)
        }
    }

    fun today(kind: String): Int {
        if (!::app.isInitialized) return 0
        val key = "c_${kind}_${dayFmt.format(Date())}"
        val persisted = app.getSharedPreferences("wuad-stat", Context.MODE_PRIVATE).getInt(key, 0)
        if (kind == "click") return todayClicks().size
        return persisted
    }

    fun dayStart(): Long {
        val c = java.util.Calendar.getInstance()
        c.set(java.util.Calendar.HOUR_OF_DAY, 0)
        c.set(java.util.Calendar.MINUTE, 0)
        c.set(java.util.Calendar.SECOND, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun todayTop(kind: String, n: Int = 5): List<Pair<String, Int>> =
        synchronized(lock) { StatAgg.top(items.toList(), kind, dayStart(), n) }

    fun todayDigest(): String = synchronized(lock) { StatAgg.digest(items.toList(), dayStart()) }

    fun clock(t: Long): String = clockFmt.format(Date(t))

    fun pickPersist(src: List<WuadEvent>): List<WuadEvent> {
        val click = src.filter { it.kind == "click" }.take(80)
        val dns = src.filter { it.kind == "dns" }.take(80)
        val other = src.filter { it.kind != "click" && it.kind != "dns" }.take(40)
        return (click + dns + other).distinctBy { it.t to it.kind to it.title }.sortedByDescending { it.t }
    }

    private fun syncClickStat() {
        if (!::app.isInitialized) return
        val key = "c_click_${dayFmt.format(Date())}"
        app.getSharedPreferences("wuad-stat", Context.MODE_PRIVATE)
            .edit().putInt(key, items.count { isClick(it) && it.t >= dayStart() }).commit()
    }

    private fun bump(kind: String, hard: Boolean = false) {
        val sp = app.getSharedPreferences("wuad-stat", Context.MODE_PRIVATE)
        val key = "c_${kind}_${dayFmt.format(Date())}"
        val e = sp.edit().putInt(key, sp.getInt(key, 0) + 1)
        if (hard) e.commit() else e.apply()
    }

    private fun loadLocked(pref: String, key: String) {
        val raw = app.getSharedPreferences(pref, Context.MODE_PRIVATE).getString(key, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val have = items.map { it.t to it.title }.toHashSet()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val ev = WuadEvent(o.getLong("t"), o.getString("k"), o.getString("title"), o.optString("sub"))
            if (have.add(ev.t to ev.title)) items.addLast(ev)
        }
    }

    private fun persistLocked(force: Boolean) {
        persistDirty++
        if (!force && persistDirty < 25) return
        persistDirty = 0
        val all = pickPersist(items.toList())
        val pins = items.filter { it.kind == "click" }.take(80)
        val e = app.getSharedPreferences("wuad-log", Context.MODE_PRIVATE).edit()
            .putString("items", toArr(all).toString())
            .putString("pins", toArr(pins).toString())
        if (force) e.commit() else e.apply()
    }

    private fun toArr(src: List<WuadEvent>): JSONArray {
        val arr = JSONArray()
        src.forEach { ev ->
            arr.put(JSONObject().put("t", ev.t).put("k", ev.kind).put("title", ev.title).put("sub", ev.sub))
        }
        return arr
    }
}
