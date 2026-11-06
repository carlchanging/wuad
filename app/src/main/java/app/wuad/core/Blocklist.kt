package app.wuad.core

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

object Blocklist {
    @Volatile
    private var hosts: Set<String> = emptySet()
    val size = AtomicInteger(0)
    @Volatile
    var ready: Boolean = false
        private set

    fun load(ctx: Context) {
        DnsSubStore.init(ctx)
        val set = HashSet<String>(180000)
        var ate = false
        DnsSubStore.enabled().forEach { sub ->
            val f = DnsSubStore.file(ctx, sub.id)
            if (f.exists() && f.length() > 32) {
                eatStream(f.inputStream(), set)
                ate = true
            } else if (sub.id == DnsSubStore.ANTI_AD) {
                eat(ctx, f, "anti-ad.txt", set)
                ate = true
            }
        }
        if (!ate) eat(ctx, File(ctx.filesDir, "anti-ad.txt"), "anti-ad.txt", set)
        hosts = set
        size.set(set.size)
        ready = true
    }

    private fun eat(ctx: Context, updated: File, asset: String, set: HashSet<String>) {
        val stream = if (updated.exists() && updated.length() > 32) {
            updated.inputStream()
        } else {
            try { ctx.assets.open(asset) } catch (_: Exception) { return }
        }
        eatStream(stream, set)
    }

    private fun eatStream(stream: java.io.InputStream, set: HashSet<String>) {
        stream.bufferedReader().useLines { lines ->
            lines.forEach { line -> HostlistParse.host(line)?.let { set.add(it) } }
        }
    }

    fun blocked(host: String): Boolean {
        if (!Prefs.dnsOn) return false
        val h = host.trim().lowercase().trimEnd('.')
        if (h.isEmpty()) return false
        val allow = RuleStore.dnsCustom().filter { it.action == "allow" }.map { it.host }
        if (allow.any { h == it || h.endsWith(".$it") }) return false
        val extra = RuleStore.dnsCustom().filter { it.action == "block" }.map { it.host }
        if (match(h, extra)) return true
        if (!RuleStore.dnsPresetOn()) return false
        if (h in RuleStore.dnsDeleted()) return false
        var cur = h
        while (true) {
            if (cur in RuleStore.dnsDeleted()) return false
            val dot = cur.indexOf('.')
            if (dot <= 0 || dot == cur.lastIndex) break
            cur = cur.substring(dot + 1)
        }
        return match(h, hosts)
    }

    fun containsPreset(host: String): Boolean {
        val h = host.trim().lowercase()
        return ready && match(h, hosts)
    }

    fun searchPreset(query: String, limit: Int = 40): List<String> {
        val q = query.trim().lowercase()
        if (!ready || q.length < 2) return emptyList()
        val del = RuleStore.dnsDeleted()
        return hosts.asSequence()
            .filter { it.contains(q) && it !in del }
            .take(limit)
            .toList()
    }

    private fun match(host: String, set: Collection<String>): Boolean {
        if (set.isEmpty()) return false
        var cur = host
        while (true) {
            if (set is Set<*>) {
                if (set.contains(cur)) return true
            } else if (set.contains(cur)) return true
            val dot = cur.indexOf('.')
            if (dot <= 0 || dot == cur.lastIndex) return false
            cur = cur.substring(dot + 1)
        }
    }
}
