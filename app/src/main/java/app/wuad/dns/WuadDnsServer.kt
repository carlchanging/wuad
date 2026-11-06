package app.wuad.dns

import app.wuad.core.AppNames
import app.wuad.core.Blocklist
import app.wuad.core.ConnLabel
import app.wuad.core.DnsUpStore
import app.wuad.core.EventStore
import app.wuad.core.Prefs
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本地 DNS 服务器。广告域回 0.0.0.0，其余转上游。
 * 分流开着时只给 nameserver-policy（geosite:cn / 广告）用；海外 fake-ip 不进这里。
 */
object WuadDnsServer {
    const val PORT = 1053

    private val alive = AtomicBoolean(false)
    private val cache = ConcurrentHashMap<String, Pair<ByteArray, Long>>()
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var sock: DatagramSocket? = null
    @Volatile private var loop: Thread? = null
    @Volatile private var tcpSock: ServerSocket? = null
    @Volatile private var tcpLoop: Thread? = null

    val running: Boolean get() = alive.get()

    @Synchronized
    fun start() {
        if (alive.get()) return
        val s = DatagramSocket(null)
        s.reuseAddress = true
        s.bind(InetSocketAddress("127.0.0.1", PORT))
        sock = s
        alive.set(true)
        loop = Thread({
            val box = ByteArray(2048)
            while (alive.get()) {
                val pack = DatagramPacket(box, box.size)
                try {
                    s.receive(pack)
                } catch (_: Exception) {
                    break
                }
                val req = box.copyOf(pack.length)
                val who = pack.socketAddress
                val peer = who as? InetSocketAddress
                pool.execute {
                    val ans = answer(
                        req,
                        peer?.address?.hostAddress.orEmpty(),
                        peer?.port ?: 0,
                        "127.0.0.1",
                        PORT
                    )
                    try {
                        s.send(DatagramPacket(ans, ans.size, who))
                    } catch (_: Exception) {
                    }
                }
            }
        }, "wuad-dns").also { it.isDaemon = true; it.start() }
        startTcp()
    }

    private fun startTcp() {
        val ss = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress("127.0.0.1", PORT))
            }
        } catch (_: Exception) {
            return
        }
        tcpSock = ss
        tcpLoop = Thread({
            while (alive.get()) {
                val c = try { ss.accept() } catch (_: Exception) { break }
                pool.execute {
                    try {
                        c.soTimeout = 4000
                        val input = DataInputStream(c.getInputStream())
                        val output = DataOutputStream(c.getOutputStream())
                        val len = input.readUnsignedShort()
                        if (len in 1..4096) {
                            val req = ByteArray(len)
                            input.readFully(req)
                            val ans = answer(
                                req,
                                (c.remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress.orEmpty(),
                                (c.remoteSocketAddress as? InetSocketAddress)?.port ?: 0,
                                "127.0.0.1",
                                PORT
                            )
                            output.writeShort(ans.size)
                            output.write(ans)
                            output.flush()
                        }
                    } catch (_: Exception) {
                    } finally {
                        try { c.close() } catch (_: Exception) {}
                    }
                }
            }
        }, "wuad-dns-tcp").also { it.isDaemon = true; it.start() }
    }

    @Synchronized
    fun stop() {
        alive.set(false)
        try { sock?.close() } catch (_: Exception) {}
        try { tcpSock?.close() } catch (_: Exception) {}
        sock = null
        tcpSock = null
        loop = null
        tcpLoop = null
        cache.clear()
    }

    fun answer(
        query: ByteArray,
        srcIp: String = "",
        srcPort: Int = 0,
        dstIp: String = "",
        dstPort: Int = 53
    ): ByteArray {
        val name = DnsCodec.readQueryName(query) ?: return DnsCodec.servfail(query)
        val qtype = qtypeName(DnsCodec.qtype(query))
        val filter = try { Prefs.dnsOn } catch (_: Exception) { false }
        if (filter && Blocklist.blocked(name)) {
            note(name, qtype, "REJECT", "anti-AD / 自定义", srcIp, srcPort, dstIp, dstPort)
            return DnsCodec.sinkhole(query)
        }
        val key = name + "|" + DnsCodec.qtype(query)
        val hit = cache[key]
        if (hit != null && hit.second > System.currentTimeMillis()) return hit.first.copyOf()
        val reply = resolve(query)
        if (reply == null) {
            note(name, qtype, "FAIL", "上游没应答", srcIp, srcPort, dstIp, dstPort)
            return DnsCodec.servfail(query)
        }
        if (filter && DnsCodec.cnames(reply).any { Blocklist.blocked(it) }) {
            note(name, qtype, "REJECT", "CNAME", srcIp, srcPort, dstIp, dstPort)
            return DnsCodec.sinkhole(query)
        }
        val rcode = reply[3].toInt() and 0x0f
        if (rcode != 0) {
            note(name, qtype, "FAIL", "rcode=$rcode", srcIp, srcPort, dstIp, dstPort)
        }
        if (reply.size >= 4 && rcode == 0) {
            cache[key] = reply to System.currentTimeMillis() + 20_000
            if (cache.size > 256) cache.keys.take(64).forEach { cache.remove(it) }
        }
        return reply
    }

    private fun note(
        name: String,
        qtype: String,
        result: String,
        why: String,
        srcIp: String,
        srcPort: Int,
        dstIp: String,
        dstPort: Int
    ) {
        val app = try {
            AppNames.ofDns(name, srcIp, srcPort, dstIp, dstPort)
        } catch (_: Exception) {
            ""
        }
        EventStore.add("dns", ConnLabel.title(app, name, qtype, result), why)
    }

    private fun qtypeName(n: Int): String = when (n) {
        1 -> "A"
        28 -> "AAAA"
        5 -> "CNAME"
        65 -> "HTTPS"
        else -> "A"
    }

    private fun resolve(query: ByteArray): ByteArray? {
        val up = try { Prefs.dnsUpstream } catch (_: Exception) { "223.5.5.5" }
        if (up.startsWith("https://")) doh(up, query)?.let { return it }
        udp(DnsUpStore.udpHost(up), query)?.let { return it }
        udp("223.5.5.5", query)?.let { return it }
        udp("223.6.6.6", query)?.let { return it }
        return null
    }

    private fun udp(host: String, dns: ByteArray): ByteArray? {
        val sock = DatagramSocket()
        return try {
            sock.soTimeout = 2000
            sock.send(DatagramPacket(dns, dns.size, InetAddress.getByName(host), 53))
            val box = ByteArray(2048)
            val incoming = DatagramPacket(box, box.size)
            sock.receive(incoming)
            box.copyOf(incoming.length)
        } catch (_: Exception) {
            null
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }

    private fun doh(url: String, dns: ByteArray): ByteArray? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/dns-message")
            conn.setRequestProperty("Accept", "application/dns-message")
            conn.connectTimeout = 2500
            conn.readTimeout = 2500
            conn.doOutput = true
            conn.outputStream.use { it.write(dns) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else null)?.readBytes()
            conn.disconnect()
            if (code == 200 && body != null && body.size >= 12) body else null
        } catch (_: Exception) {
            null
        }
    }
}
