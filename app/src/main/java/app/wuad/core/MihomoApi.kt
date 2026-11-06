package app.wuad.core

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ProxyNode(
    val name: String,
    val type: String,
    val now: Boolean,
    var delay: Int = 0
)

data class LiveConn(
    val id: String,
    val host: String,
    val rule: String,
    val chain: String,
    val network: String = "",
    val process: String = "",
    val destPort: String = "",
    val uid: Int = 0,
    val srcIp: String = "",
    val srcPort: Int = 0,
    val dstIp: String = "",
    val inbound: String = ""
)

object MihomoApi {
    const val BASE = "http://127.0.0.1:19090"

    fun alive(): Boolean = try {
        get("/version").contains("version")
    } catch (_: Exception) {
        false
    }

    fun shutdown() {
        try { open("POST", "/shutdown", "{}", 800, 800) } catch (_: Exception) {}
    }

    fun setMode(mode: String) {
        val keep = try { groupOf("PROXY").first } catch (_: Exception) { "" }
        patch("/configs", JSONObject().put("mode", mode).toString())
        if (mode == "global" && keep.isNotBlank()) {
            try { selectIn("GLOBAL", keep) } catch (_: Exception) {}
        }
    }

    fun activeGroup(mode: String? = null): String {
        val m = mode ?: try { Prefs.mode } catch (_: Exception) { "rule" }
        return if (m == "global") "GLOBAL" else "PROXY"
    }

    fun groupNow(): Pair<String, List<ProxyNode>> = try {
        groupOf(activeGroup())
    } catch (_: Exception) {
        groupOf("PROXY")
    }

    /** 分流关着也从本地订阅文件列节点，跟 FLClash 一样。 */
    fun visibleNodes(): Pair<String, List<ProxyNode>> {
        if (alive()) {
            try {
                val live = liveVisible()
                if (live.first.isNotBlank()) remember(live.first)
                if (live.second.any { it.name != "AUTO" && it.name != "DIRECT" }) return live
            } catch (_: Exception) {
            }
        }
        val now = SubFetch.selectedNow()
        return now to SubFetch.catalog(now)
    }

    private fun liveVisible(): Pair<String, List<ProxyNode>> {
        val (now, proxy) = groupNow()
        val seen = LinkedHashSet<String>()
        val out = ArrayList<ProxyNode>()
        fun add(n: ProxyNode, keepShell: Boolean) {
            if (!keepShell && isShell(n.name, n.type)) return
            if (seen.add(n.name)) out.add(n.copy(now = n.name == now))
        }
        proxy.forEach { add(it, it.name == "AUTO" || it.name == "DIRECT") }
        try {
            groupOf("AUTO").second.forEach { add(it, false) }
        } catch (_: Exception) {
        }
        providerNodes().forEach { add(it, false) }
        return now to out
    }

    private fun remember(name: String) {
        if (name.isBlank()) return
        try { Prefs.node = name } catch (_: Exception) {}
    }

    fun providerNodes(): List<ProxyNode> {
        val want = try { SubStore.ids().toSet() } catch (_: Exception) { emptySet() }
        val root = try {
            JSONObject(get("/providers/proxies"))
        } catch (_: Exception) {
            return emptyList()
        }
        val providers = root.optJSONObject("providers") ?: return emptyList()
        val out = ArrayList<ProxyNode>()
        val keys = providers.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val p = providers.optJSONObject(key) ?: continue
            if (!isAirportProvider(key, p.optString("vehicleType"), want)) continue
            val arr = p.optJSONArray("proxies") ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name")
                val type = o.optString("type", "Unknown")
                if (name.isBlank() || isShell(name, type)) continue
                out.add(ProxyNode(name, type, false))
            }
        }
        return out
    }

    fun groupOf(group: String): Pair<String, List<ProxyNode>> {
        val root = JSONObject(get("/proxies"))
        val all = root.getJSONObject("proxies")
        val g = all.optJSONObject(group)
            ?: all.optJSONObject("PROXY")
            ?: return "DIRECT" to listOf(ProxyNode("DIRECT", "Direct", true))
        val now = g.optString("now", "DIRECT")
        val names = g.optJSONArray("all")
        val out = ArrayList<ProxyNode>()
        if (names != null) {
            for (i in 0 until names.length()) {
                val n = names.getString(i)
                val meta = all.optJSONObject(n)
                out.add(ProxyNode(n, meta?.optString("type", "Unknown") ?: "Unknown", n == now))
            }
        }
        if (out.none { it.name == "DIRECT" }) out.add(0, ProxyNode("DIRECT", "Direct", now == "DIRECT"))
        return now to out
    }

    fun select(name: String) {
        remember(name)
        if (!alive()) return
        selectIn("PROXY", name)
        try { selectIn("GLOBAL", name) } catch (_: Exception) {}
    }

    private fun selectIn(group: String, name: String) {
        put("/proxies/${encodeProxy(group)}", JSONObject().put("name", name).toString())
    }

    fun delay(name: String): Int {
        val enc = encodeProxy(name)
        val body = open("GET", "/proxies/$enc/delay?$delayQuery", null, 3000, 12000)
        val n = JSONObject(body).optInt("delay", 0)
        return if (n > 0) n else -1
    }

    /** FLClash 同款：一组并行测速，避免节点名空格被编成 + 后 404。 */
    fun delayGroup(group: String = "PROXY"): Map<String, Int> {
        val enc = encodeProxy(group)
        val body = open("GET", "/group/$enc/delay?$delayQuery", null, 3000, 60000)
        val obj = JSONObject(body)
        val out = LinkedHashMap<String, Int>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val n = obj.optInt(k, 0)
            out[k] = if (n > 0) n else -1
        }
        return out
    }

    fun encodeProxy(name: String): String =
        java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")

    private val delayQuery: String
        get() = "timeout=8000&url=" + java.net.URLEncoder.encode(
            "https://www.gstatic.com/generate_204",
            "UTF-8"
        )

    fun updateSub(): String {
        val items = try { SubStore.all() } catch (_: Exception) { emptyList() }
        if (items.isEmpty()) throw IllegalStateException("还没添加机场订阅")
        val errs = ArrayList<String>()
        var pulled = 0
        val kernel = alive()
        items.forEach { item ->
            try {
                pulled += SubFetch.pull(item)
            } catch (e: Exception) {
                errs += e.message ?: "失败"
            }
            if (kernel) {
                try {
                    open("PUT", "/providers/proxies/${encodeProxy(item.id)}", "", 4000, 15000)
                } catch (e: Exception) {
                    errs += e.message ?: "内核没吃到文件"
                }
            }
        }
        if (kernel) Thread.sleep(400)
        val n = if (kernel) {
            try { providerNodes().size } catch (_: Exception) { SubFetch.catalogCount() }
        } else {
            SubFetch.catalogCount()
        }
        if (n > 0) return "订阅已刷新，$n 个节点"
        if (pulled > 0) return "订阅已刷新，$pulled 个节点"
        throw IllegalStateException(
            if (errs.isNotEmpty()) errs.joinToString("；") else "机场没返回 Clash 节点"
        )
    }

    fun isShell(name: String, type: String): Boolean {
        if (name in SHELL_NAMES) return true
        return type.lowercase() in GROUP_TYPES
    }

    private fun isAirportProvider(name: String, vehicle: String, want: Set<String>): Boolean {
        if (name.equals("default", true)) return false
        if (vehicle.equals("Compatible", true)) return false
        return want.isEmpty() || name in want
    }

    private val SHELL_NAMES = setOf("DIRECT", "REJECT", "COMPATIBLE", "PASS", "GLOBAL", "PROXY", "AUTO", "DNS")
    private val GROUP_TYPES = setOf(
        "direct", "reject", "compatible", "pass", "dns",
        "selector", "urltest", "fallback", "loadbalance", "relay"
    )

    fun connections(): List<LiveConn> {
        val root = JSONObject(get("/connections"))
        val arr = root.optJSONArray("connections") ?: return emptyList()
        val out = ArrayList<LiveConn>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val meta = o.optJSONObject("metadata") ?: JSONObject()
            val host = meta.optString("host").ifBlank { meta.optString("destinationIP") }
            if (host.isBlank() || host.startsWith("127.") || host == "::1") continue
            val chains = o.optJSONArray("chains")
            val chain = if (chains != null && chains.length() > 0) chains.getString(0) else ""
            val dstIp = meta.optString("destinationIP")
            val srcPort = meta.optInt("sourcePort", 0).let { if (it > 0) it else meta.optString("sourcePort").toIntOrNull() ?: 0 }
            val dstPort = meta.optString("destinationPort").ifBlank {
                meta.optInt("destinationPort", 0).takeIf { it > 0 }?.toString().orEmpty()
            }
            out.add(
                LiveConn(
                    o.optString("id"),
                    host,
                    o.optString("rule"),
                    chain,
                    meta.optString("network"),
                    meta.optString("process").ifBlank { meta.optString("processPath") },
                    dstPort,
                    meta.optInt("uid", 0),
                    meta.optString("sourceIP"),
                    srcPort,
                    dstIp,
                    meta.optString("type")
                )
            )
            if (ConnLabel.isFakeIp(dstIp) && host.isNotBlank()) AppNames.rememberFakeIp(host, dstIp)
        }
        return out
    }

    fun trafficBytes(): Long {
        val root = JSONObject(get("/connections"))
        return root.optLong("uploadTotal") + root.optLong("downloadTotal")
    }

    fun configs(): JSONObject = JSONObject(get("/configs"))

    fun setInterfaceName(name: String) {
        if (name.isBlank()) return
        patch("/configs", JSONObject().put("interface-name", name).toString())
    }

    fun dnsQuery(name: String, type: String = "A"): String {
        val enc = encodeProxy(name)
        val body = open("GET", "/dns/query?name=$enc&type=$type", null, 2500, 8000)
        val obj = JSONObject(body)
        val status = obj.optInt("Status", -1)
        val ans = obj.optJSONArray("Answer")
        val data = if (ans != null && ans.length() > 0) {
            (0 until ans.length()).joinToString(",") { i ->
                ans.getJSONObject(i).optString("data")
            }
        } else ""
        return if (data.isNotBlank()) data else "Status=$status 无记录"
    }

    private fun get(path: String): String = open("GET", path, null, 2500, 5000)
    private fun put(path: String, body: String): String = open("PUT", path, body, 2500, 8000)
    private fun patch(path: String, body: String): String = open("PATCH", path, body, 2500, 5000)

    private fun open(method: String, path: String, body: String?, connectMs: Int, readMs: Int): String {
        val conn = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectMs
            readTimeout = readMs
            setRequestProperty("Content-Type", "application/json")
            if (body != null) {
                doOutput = true
                outputStream.use { it.write(body.toByteArray()) }
            }
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code $text")
            text
        } finally {
            conn.disconnect()
        }
    }
}
