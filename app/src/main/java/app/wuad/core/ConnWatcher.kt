package app.wuad.core

import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

object ConnWatcher {
    private val dead = AtomicBoolean(true)
    private val seen = HashSet<String>()
    private var logPos = 0L

    fun start(ctx: android.content.Context) {
        dead.set(false)
        logPos = 0L
        AppNames.init(ctx)
        Thread({
            while (!dead.get()) {
                try {
                    pullConns()
                    pullLog(ctx)
                } catch (_: Exception) {
                }
                try {
                    Thread.sleep(2000)
                } catch (_: Exception) {
                    break
                }
            }
        }, "wuad-conn").apply { isDaemon = true; start() }
        Thread({
            while (!dead.get()) {
                try {
                    pumpApiLogs()
                } catch (_: Exception) {
                }
                try {
                    Thread.sleep(800)
                } catch (_: Exception) {
                    break
                }
            }
        }, "wuad-logs").apply { isDaemon = true; start() }
    }

    fun stop() {
        dead.set(true)
        synchronized(seen) { seen.clear() }
        logPos = 0L
    }

    private fun pullConns() {
        if (!MihomoApi.alive()) return
        MihomoApi.connections().forEach { c ->
            val key = c.id.ifBlank { c.host + c.rule }
            val fresh = synchronized(seen) { seen.add(key) }
            if (!fresh) return@forEach
            val reject = c.rule.contains("REJECT", true) || c.chain.contains("REJECT", true)
            val qtype = c.network.uppercase().ifBlank { "TCP" }
            val result = c.chain.ifBlank { if (reject) "REJECT" else "DIRECT" }
            val app = AppNames.ofConn(c)
            EventStore.add(
                if (reject) "dns" else "conn",
                ConnLabel.title(app, c.host, qtype, result),
                ConnLabel.sub(c.rule, c.destPort)
            )
        }
        synchronized(seen) {
            if (seen.size > 800) seen.clear()
        }
    }

    private fun pullLog(ctx: android.content.Context) {
        val f = java.io.File(ClashConfig.workDir(ctx), "mihomo.log")
        if (!f.exists()) return
        val raf = RandomAccessFile(f, "r")
        try {
            if (logPos > raf.length()) logPos = 0
            raf.seek(logPos)
            var line = raf.readLine()
            while (line != null) {
                val s = String(line.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
                ingest(s)
                line = raf.readLine()
            }
            logPos = raf.filePointer
        } finally {
            raf.close()
        }
    }

    private fun pumpApiLogs() {
        if (!MihomoApi.alive()) return
        val conn = (URL(MihomoApi.BASE + "/logs?level=info").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 2000
            readTimeout = 15000
        }
        try {
            if (conn.responseCode !in 200..299) return
            conn.inputStream.bufferedReader().use { reader ->
                while (!dead.get()) {
                    val line = reader.readLine() ?: break
                    ingest(line)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun ingest(line: String) {
        val hit = DnsLogParse.parse(line) ?: return
        if (hit.result.startsWith("FAKE-IP ")) {
            AppNames.rememberFakeIp(hit.host, hit.result.removePrefix("FAKE-IP ").trim())
        }
        val tcpish = hit.qtype == "TCP" || hit.qtype == "UDP"
        if (tcpish && hit.result != "REJECT" && hit.result != "FAIL") {
            return
        }
        val key = "log:${hit.host}:${hit.qtype}:${hit.result}"
        val fresh = synchronized(seen) { seen.add(key) }
        if (!fresh) return
        val app = AppNames.ofLocal(hit.srcIp, hit.srcPort)
        EventStore.add(
            if (hit.result == "REJECT" || line.contains("[DNS]", true)) "dns" else "conn",
            ConnLabel.title(app, hit.host, hit.qtype, hit.result),
            hit.extra.ifBlank { "mihomo" }
        )
    }
}
