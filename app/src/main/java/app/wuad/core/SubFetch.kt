package app.wuad.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/**
 * FLClash 同款：App 侧拉订阅，不走 mihomo http provider。
 * 隧道起来时内核还没节点，让它自己去拉等于自己卡死自己。
 */
object SubFetch {
    private lateinit var app: Context

    val userAgents = listOf(
        "clash.meta",
        "FlClash/0.8.90 clash-verge/v1.6.6 Platform/android",
        "ClashMetaForAndroid/2.11.16"
    )

    private val share = Regex(
        "^(ss|ssr|vmess|vless|trojan|hysteria2?|hy2|tuic|anytls|socks5?|http)://",
        RegexOption.IGNORE_CASE
    )
    private val nameItem = Regex("""^\s*-\s*name\s*:""")
    private val inlineItem = Regex("""^\s*-\s*\{[^}]*name\s*:""")
    private val topKey = Regex("""^[A-Za-z0-9_-]+\s*:""")

    fun init(ctx: Context) {
        app = ctx.applicationContext
    }

    fun file(id: String): File = File(ClashConfig.workDir(app), "providers/$id.yaml")

    fun ensureAll() {
        if (!::app.isInitialized) return
        SubStore.all().forEach { item ->
            val f = file(item.id)
            if (f.exists() && countNodes(f.readText()) > 0) return@forEach
            try {
                pull(item)
            } catch (e: Exception) {
                EventStore.add("sys", "订阅没拉下来", e.message ?: "")
            }
        }
    }

    fun pull(item: SubItem): Int {
        if (!::app.isInitialized) throw IllegalStateException("订阅模块没初始化")
        var last = "空响应"
        for (ua in userAgents) {
            val raw = try {
                download(item.url, ua)
            } catch (e: Exception) {
                last = e.message ?: "下载失败"
                continue
            }
            val yaml = normalize(raw)
            val n = countNodes(yaml)
            if (n > 0) {
                write(item.id, yaml)
                return n
            }
            last = "内容不像节点（换 UA 再试）"
        }
        throw IllegalStateException(last)
    }

    fun normalize(raw: ByteArray): String {
        var text = String(maybeDecompress(raw), Charsets.UTF_8).trim().removePrefix("\uFEFF")
        if (looksBase64(text)) {
            val dec = decodeB64(text)
            if (dec != null && looksNodes(dec)) text = dec
        }
        if (looksShareLinks(text) || looksSip008(text)) return text.trim() + "\n"
        extractProxies(text)?.let { return it }
        if (nameItem.containsMatchIn(text) || inlineItem.containsMatchIn(text)) {
            return "proxies:\n${text.trim()}\n"
        }
        return text.trim() + "\n"
    }

    fun extractProxies(text: String): String? {
        val lines = text.lines()
        val start = lines.indexOfFirst { it.trimStart().startsWith("proxies:") }
        if (start < 0) return null
        val out = ArrayList<String>()
        out.add("proxies:")
        for (i in start + 1 until lines.size) {
            val line = lines[i]
            val t = line.trimStart()
            if (t.isNotEmpty() && !t.startsWith("#") && !line.startsWith(" ") &&
                !line.startsWith("\t") && topKey.containsMatchIn(t)
            ) {
                break
            }
            out.add(line)
        }
        val body = out.drop(1).joinToString("\n").trim()
        if (body.isBlank() || !looksNodes(body)) return null
        return out.joinToString("\n").trimEnd() + "\n"
    }

    fun catalog(now: String = selectedNow()): List<ProxyNode> {
        val out = ArrayList<ProxyNode>()
        val seen = LinkedHashSet<String>()
        fun add(n: ProxyNode) {
            if (seen.add(n.name)) out.add(n.copy(now = n.name == now))
        }
        add(ProxyNode("AUTO", "URLTest", now == "AUTO"))
        add(ProxyNode("DIRECT", "Direct", now == "DIRECT"))
        if (!::app.isInitialized) return out
        try {
            SubStore.all().forEach { item ->
                val f = file(item.id)
                if (!f.exists() || f.length() < 8) return@forEach
                parseNodes(f.readText(), item.name).forEach { add(it) }
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun catalogCount(): Int = catalog("").count { it.name != "AUTO" && it.name != "DIRECT" }

    fun parseNodes(text: String, prefix: String = ""): List<ProxyNode> {
        val out = ArrayList<ProxyNode>()
        val seen = LinkedHashSet<String>()
        fun add(raw: String, type: String) {
            val name = stripQuote(raw)
            if (name.isBlank() || MihomoApi.isShell(name, type)) return
            val shown = if (prefix.isBlank()) name else "[$prefix] $name"
            if (seen.add(shown)) out.add(ProxyNode(shown, type.ifBlank { "Proxy" }, false))
        }
        var pending: String? = null
        text.lines().forEach { line ->
            val inline = INLINE_NAME.find(line)
            if (inline != null) {
                add(inline.groupValues[1], TYPE.find(line)?.groupValues[1] ?: "Proxy")
                return@forEach
            }
            val named = YAML_NAME.find(line)
            if (named != null) {
                pending?.let { add(it, "Proxy") }
                pending = named.groupValues[1]
                val t = TYPE.find(line)?.groupValues[1]
                if (t != null) {
                    add(pending!!, t)
                    pending = null
                }
                return@forEach
            }
            if (pending != null) {
                val t = TYPE.find(line)?.groupValues[1]
                if (t != null) {
                    add(pending!!, t)
                    pending = null
                }
            }
        }
        pending?.let { add(it, "Proxy") }
        if (out.isEmpty() && looksShareLinks(text)) {
            text.lines().forEach { line ->
                val t = line.trim()
                if (!share.containsMatchIn(t)) return@forEach
                val scheme = t.substringBefore("://")
                val hash = t.substringAfter('#', "")
                val label = if (hash.isBlank()) scheme else try {
                    java.net.URLDecoder.decode(hash, "UTF-8")
                } catch (_: Exception) {
                    hash
                }
                add(label, scheme)
            }
        }
        return out
    }

    fun selectedNow(): String = try {
        Prefs.node.ifBlank { "AUTO" }
    } catch (_: Exception) {
        "AUTO"
    }

    private fun stripQuote(s: String): String {
        var t = s.trim().trimEnd(',')
        if (t.length >= 2 &&
            ((t.startsWith('"') && t.endsWith('"')) || (t.startsWith('\'') && t.endsWith('\'')))
        ) {
            t = t.substring(1, t.length - 1)
        }
        return t.trim()
    }

    private val YAML_NAME = Regex("""^\s*-\s*name\s*:\s*(.+)$""")
    private val INLINE_NAME = Regex("""^\s*-\s*\{[^}]*name\s*:\s*([^,}]+)""")
    private val TYPE = Regex("""type\s*:\s*([A-Za-z0-9_-]+)""")

    fun countNodes(text: String): Int {
        val shareN = text.lines().count { share.containsMatchIn(it.trim()) }
        if (shareN > 0) return shareN
        return text.lineSequence().count { nameItem.containsMatchIn(it) || inlineItem.containsMatchIn(it) }
    }

    fun looksShareLinks(text: String): Boolean {
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }
        if (lines.isEmpty()) return false
        val hit = lines.count { share.containsMatchIn(it) }
        return hit > 0 && hit * 2 >= lines.size
    }

    private fun looksSip008(text: String): Boolean {
        val t = text.trimStart()
        return t.startsWith("{") && t.contains("\"servers\"")
    }

    private fun looksNodes(text: String): Boolean =
        looksShareLinks(text) || looksSip008(text) ||
            nameItem.containsMatchIn(text) || inlineItem.containsMatchIn(text)

    private fun looksBase64(text: String): Boolean {
        val compact = text.replace("\\s".toRegex(), "")
        if (compact.length < 16 || compact.length % 4 != 0) return false
        return compact.matches(Regex("^[A-Za-z0-9+/=]+$"))
    }

    private fun decodeB64(text: String): String? = try {
        val bytes = Base64.getDecoder().decode(text.replace("\\s".toRegex(), ""))
        String(maybeDecompress(bytes), Charsets.UTF_8).trim().removePrefix("\uFEFF")
    } catch (_: Exception) {
        null
    }

    fun maybeDecompress(bytes: ByteArray): ByteArray {
        if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            return GZIPInputStream(ByteArrayInputStream(bytes)).readBytes()
        }
        if (bytes.size >= 2 && bytes[0] == 0x78.toByte()) {
            return try {
                InflaterInputStream(ByteArrayInputStream(bytes)).readBytes()
            } catch (_: Exception) {
                bytes
            }
        }
        return bytes
    }

    private fun write(id: String, yaml: String) {
        val dest = file(id)
        dest.parentFile?.mkdirs()
        val tmp = File(dest.absolutePath + ".part")
        tmp.writeText(yaml)
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }

    fun touchEmpty(id: String) {
        val dest = file(id)
        if (dest.exists()) return
        dest.parentFile?.mkdirs()
        dest.writeText("proxies: []\n")
    }

    private fun download(url: String, ua: String): ByteArray {
        val target = authTarget(url)
        val conn = (open(target.first).apply {
            connectTimeout = 15000
            readTimeout = 45000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", ua)
            setRequestProperty("Accept", "*/*")
            target.second?.let { setRequestProperty("Authorization", it) }
        })
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.isEmpty()) throw IllegalStateException("HTTP $code 空包")
            if (bytes.size > 8 * 1024 * 1024) throw IllegalStateException("订阅超过 8MB")
            return bytes
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val u = URL(url)
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val net = cm?.allNetworks?.firstOrNull { n ->
            val c = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
            !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        return (if (net != null) net.openConnection(u) else u.openConnection()) as HttpURLConnection
    }

    private fun authTarget(url: String): Pair<String, String?> {
        val uri = try { URI(url) } catch (_: Exception) { return url to null }
        val info = uri.userInfo
        if (info.isNullOrBlank()) return url to null
        val token = Base64.getEncoder().encodeToString(info.toByteArray(Charsets.UTF_8))
        val clean = URI(uri.scheme, null, uri.host, uri.port, uri.path, uri.query, uri.fragment).toString()
        return clean to "Basic $token"
    }
}
