package app.wuad.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SubItem(
    val id: String,
    var name: String,
    var url: String
)

object SubStore {
    private lateinit var app: Context
    private val items = ArrayList<SubItem>()

    fun init(ctx: Context) {
        app = ctx.applicationContext
        load()
    }

    fun all(): List<SubItem> = synchronized(items) { items.toList() }

    fun add(name: String, url: String): SubItem? {
        val u = url.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) return null
        val item = SubItem("s-" + UUID.randomUUID().toString().take(8), name.trim().ifEmpty { "订阅" }, u)
        synchronized(items) { items.add(item) }
        persist()
        return item
    }

    fun edit(id: String, name: String, url: String): Boolean {
        val u = url.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) return false
        val hit = synchronized(items) { items.find { it.id == id } } ?: return false
        hit.name = name.trim().ifEmpty { hit.name }
        hit.url = u
        persist()
        return true
    }

    fun delete(id: String) {
        synchronized(items) { items.removeAll { it.id == id } }
        persist()
    }

    fun ids(): List<String> = all().map { it.id }

    private fun load() {
        items.clear()
        val raw = app.getSharedPreferences("wuad", Context.MODE_PRIVATE).getString("subs", "")
        if (!raw.isNullOrBlank()) {
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    items.add(SubItem(o.optString("id", "s-$i"), o.optString("name"), o.optString("url")))
                }
            } catch (_: Exception) {
            }
        }
        if (items.isEmpty()) {
            val legacy = Prefs.subUrl.trim()
            if (legacy.startsWith("http")) {
                items.add(SubItem("sub", "默认订阅", legacy))
                persist()
            }
        }
        if (items.isNotEmpty()) Prefs.subUrl = items.first().url
    }

    private fun persist() {
        val arr = JSONArray()
        synchronized(items) {
            items.forEach { s ->
                arr.put(JSONObject().put("id", s.id).put("name", s.name).put("url", s.url))
            }
        }
        app.getSharedPreferences("wuad", Context.MODE_PRIVATE).edit().putString("subs", arr.toString()).apply()
        Prefs.subUrl = items.firstOrNull()?.url ?: ""
    }
}
