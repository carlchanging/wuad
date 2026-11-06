package app.wuad.core

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.system.OsConstants
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

object AppNames {
    private const val KERNEL = "内核"
    private var app: Context? = null
    private val uidLabel = ConcurrentHashMap<Int, String>()
    private val localLabel = ConcurrentHashMap<String, String>()
    private val fakeIp = ConcurrentHashMap<String, String>()
    private val hostLabel = ConcurrentHashMap<String, String>()
    @Volatile private var peekAt = 0L

    fun init(ctx: Context) {
        app = ctx.applicationContext
    }

    fun rememberFakeIp(host: String, ip: String) {
        if (host.isBlank() || !ConnLabel.isFakeIp(ip)) return
        fakeIp[host.lowercase()] = ip
    }

    fun fakeIpOf(host: String): String = fakeIp[host.lowercase()].orEmpty()

    fun ofConn(c: LiveConn): String {
        if (c.inbound.equals("Inner", true) || c.host == "8.8.8.8" || c.host == "1.1.1.1") {
            return KERNEL
        }
        if (c.process.isNotBlank()) {
            val fromProc = labelOfPackage(c.process.substringAfterLast('/').substringBefore(':'))
            if (fromProc.isNotBlank()) return tagged(c, fromProc)
        }
        if (c.uid > 0) {
            val byUid = labelOfUid(c.uid)
            if (byUid.isNotBlank()) return tagged(c, byUid)
        }
        localLabel[localKey(c.srcIp, c.srcPort)]?.let { return it }
        if (c.srcIp.isBlank() || c.srcPort <= 0) return ""
        val dests = ConnLabel.destCandidates(c.dstIp, fakeIpOf(c.host))
        for (dst in dests) {
            val uid = ownerUid(c.network, c.srcIp, c.srcPort, dst, c.destPort.toIntOrNull() ?: 0)
            if (uid > 0) {
                val name = labelOfUid(uid)
                if (name.isNotBlank()) return tagged(c, name)
            }
        }
        return ""
    }

    private fun tagged(c: LiveConn, name: String): String {
        rememberHost(c.host, name)
        return rememberLocal(c, name)
    }

    fun ofLocal(srcIp: String, srcPort: Int): String {
        if (srcIp.isBlank() || srcPort <= 0) return ""
        return localLabel[localKey(srcIp, srcPort)].orEmpty()
    }

    fun rememberHost(host: String, name: String) {
        if (host.isBlank() || name.isBlank() || name == KERNEL) return
        hostLabel[host.lowercase()] = name
        if (hostLabel.size > 800) hostLabel.keys.take(200).forEach { hostLabel.remove(it) }
    }

    fun ofHost(host: String): String = hostLabel[host.lowercase()].orEmpty()

    fun ofDns(host: String, srcIp: String = "", srcPort: Int = 0, dstIp: String = "", dstPort: Int = 53): String {
        val fromTuple = ofTuple("udp", srcIp, srcPort, dstIp, dstPort)
        if (fromTuple.isNotBlank()) {
            rememberHost(host, fromTuple)
            return fromTuple
        }
        ofLocal(srcIp, srcPort).takeIf { it.isNotBlank() }?.let {
            rememberHost(host, it)
            return it
        }
        ofHost(host).takeIf { it.isNotBlank() }?.let { return it }
        peekConns()
        return ofHost(host)
    }

    private fun ofTuple(network: String, srcIp: String, srcPort: Int, dstIp: String, dstPort: Int): String {
        if (srcIp.isBlank() || srcPort <= 0 || srcIp.startsWith("127.") || srcIp == "::1") return ""
        val dests = ConnLabel.destCandidates(dstIp, "")
        val ports = if (dstPort > 0) listOf(dstPort) else listOf(53)
        for (dst in dests.ifEmpty { listOf(dstIp).filter { it.isNotBlank() } }) {
            for (p in ports) {
                val uid = ownerUid(network, srcIp, srcPort, dst, p)
                if (uid > 0) {
                    val name = labelOfUid(uid)
                    if (name.isNotBlank()) return name
                }
            }
        }
        return ""
    }

    private fun peekConns() {
        val now = System.currentTimeMillis()
        if (now - peekAt < 400) return
        peekAt = now
        try {
            MihomoApi.connections().forEach { c ->
                val app = ofConn(c)
                if (app.isNotBlank() && app != KERNEL) rememberHost(c.host, app)
            }
        } catch (_: Exception) {
        }
    }

    fun labelOfUid(uid: Int): String {
        if (uid <= 0) return ""
        uidLabel[uid]?.let { return it }
        val ctx = app ?: return ""
        val pm = ctx.packageManager
        val pkgs = try {
            pm.getPackagesForUid(uid) ?: pm.getPackagesForUid(uid % 100000)
        } catch (_: Exception) {
            null
        }
        val pkg = pkgs?.firstOrNull()
        val raw = when {
            pkg != null -> labelOfPackage(pkg)
            else -> try {
                pm.getNameForUid(uid)?.substringBefore(':').orEmpty()
            } catch (_: Exception) {
                ""
            }
        }
        val name = ConnLabel.dualSuffix(uid, raw)
        if (name.isNotBlank()) uidLabel[uid] = name
        return name
    }

    private fun labelOfPackage(pkg: String): String {
        if (pkg.isBlank() || pkg == "app.wuad") return if (pkg == "app.wuad") "无广告" else ""
        val pm = app?.packageManager ?: return pkg
        return try {
            val ai = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(ai).toString().ifBlank { pkg }
        } catch (_: PackageManager.NameNotFoundException) {
            pkg
        } catch (_: Exception) {
            pkg
        }
    }

    private fun rememberLocal(c: LiveConn, name: String): String {
        if (c.srcPort > 0 && c.srcIp.isNotBlank()) {
            localLabel[localKey(c.srcIp, c.srcPort)] = name
            if (localLabel.size > 800) localLabel.keys.take(200).forEach { localLabel.remove(it) }
        }
        return name
    }

    private fun localKey(ip: String, port: Int) = "$ip:$port"

    private fun ownerUid(network: String, srcIp: String, srcPort: Int, dstIp: String, dstPort: Int): Int {
        if (Build.VERSION.SDK_INT < 29 || dstPort <= 0) return -1
        val ctx = app ?: return -1
        val proto = if (network.equals("udp", true)) OsConstants.IPPROTO_UDP else OsConstants.IPPROTO_TCP
        return try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return -1
            cm.getConnectionOwnerUid(
                proto,
                InetSocketAddress(srcIp, srcPort),
                InetSocketAddress(dstIp, dstPort)
            )
        } catch (_: Exception) {
            -1
        }
    }
}
