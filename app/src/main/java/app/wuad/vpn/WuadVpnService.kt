package app.wuad.vpn

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import app.wuad.R
import app.wuad.core.ClashConfig
import app.wuad.core.EventStore
import app.wuad.core.MihomoApi
import app.wuad.core.MihomoEngine
import app.wuad.core.Prefs
import app.wuad.dns.WuadDnsServer
import app.wuad.ui.MainActivity
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class WuadVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var loop: DnsTunLoop? = null
    private var netCb: ConnectivityManager.NetworkCallback? = null
    private val bootGen = AtomicInteger(0)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                startTunnel()
                return START_STICKY
            }
        }
        startForeground(42, notify())
        startTunnel()
        return START_STICKY
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopTunnel()
        stopSelf()
    }

    private fun startTunnel() {
        stopTunnel()
        val full = Prefs.routeOn
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(1500)
            .addAddress(ClashConfig.TUN_ADDR, ClashConfig.TUN_PREFIX)
            .setBlocking(!full)
        if (ClashConfig.TUN_IPV6) {
            try {
                builder.addAddress(ClashConfig.TUN_ADDR6, ClashConfig.TUN_PREFIX6)
            } catch (_: Exception) {
            }
        }
        ClashConfig.vpnDnsServers.forEach { ip ->
            if (!ClashConfig.TUN_IPV6 && ClashConfig.isIpv6(ip)) return@forEach
            try {
                builder.addDnsServer(ip)
            } catch (_: Exception) {
            }
        }
        if (full) {
            builder.addRoute("0.0.0.0", 0)
            if (ClashConfig.TUN_IPV6) {
                try {
                    builder.addRoute("::", 0)
                } catch (_: Exception) {
                }
            }
        }
        ClashConfig.vpnDnsServers.forEach { ip ->
            if (!ClashConfig.TUN_IPV6 && ClashConfig.isIpv6(ip)) return@forEach
            try {
                builder.addRoute(ip, ClashConfig.dnsRoutePrefix(ip))
            } catch (_: Exception) {
            }
        }
        try {
            builder.addDisallowedApplication(packageName)
        } catch (_: Exception) {
        }
        Prefs.excluded().forEach { pkg ->
            if (pkg != packageName) {
                try {
                    builder.addDisallowedApplication(pkg)
                } catch (_: Exception) {
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            builder.setMetered(false)
        }
        applyUnderlying(builder)
        val fd = builder.establish()
        if (fd == null) {
            EventStore.add("sys", "VPN 建立失败", "系统没给出隧道")
            stopSelf()
            return
        }
        tun = fd
        running.set(true)
        watchUnderlying()
        if (Prefs.dnsOn) {
            try {
                WuadDnsServer.start()
                EventStore.add("sys", "DNS 服务器 :${WuadDnsServer.PORT}", "广告域 0.0.0.0，其余转上游")
            } catch (e: Exception) {
                EventStore.add("sys", "DNS 服务器没起来", e.message ?: "")
            }
        }
        if (full) {
            val gen = bootGen.get()
            val rawFd = fd.fd
            thread(name = "mihomo-boot", isDaemon = true) { bootEngine(rawFd, gen) }
            EventStore.add("sys", "隧道已开启", "分流走 mihomo · DNS 不进规则 REJECT")
        } else {
            loop = DnsTunLoop(fd)
            loop?.start()
            EventStore.add("sys", "DNS 过滤已开启", "UDP/TCP 53 + IPv6，流量走网卡")
        }
    }

    private fun bootEngine(rawFd: Int, gen: Int) {
        if (gen != bootGen.get()) return
        val ok = MihomoEngine.start(this, rawFd)
        if (gen != bootGen.get()) {
            MihomoEngine.stop()
            return
        }
        if (ok) {
            EventStore.add("sys", "mihomo 已启动 pid=${MihomoEngine.pid}", comboHint())
            return
        }
        EventStore.add("sys", "mihomo 没起来，回退 DNS 隧道", MihomoEngine.lastError)
        val pfd = tun ?: return
        loop = DnsTunLoop(pfd)
        loop?.start()
    }

    private fun applyUnderlying(builder: Builder? = null) {
        if (Build.VERSION.SDK_INT < 22) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val nets = cm.allNetworks.filter { n ->
            val c = cm.getNetworkCapabilities(n) ?: return@filter false
            !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }.toTypedArray()
        val pick = nets.ifEmpty { null }
        try {
            builder?.setUnderlyingNetworks(pick)
            setUnderlyingNetworks(pick)
        } catch (_: Exception) {
        }
        val iface = pick?.firstOrNull()?.let { n ->
            try { cm.getLinkProperties(n)?.interfaceName } catch (_: Exception) { null }
        }.orEmpty()
        if (MihomoEngine.running && iface.isNotBlank()) {
            try { MihomoApi.setInterfaceName(iface) } catch (_: Exception) {}
        }
    }

    private fun watchUnderlying() {
        dropUnderlying()
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { applyUnderlying() }
            override fun onLost(network: Network) { applyUnderlying() }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                applyUnderlying()
            }
        }
        netCb = cb
        try {
            cm.registerNetworkCallback(req, cb)
        } catch (_: Exception) {
            netCb = null
        }
        applyUnderlying()
    }

    private fun dropUnderlying() {
        val cb = netCb ?: return
        netCb = null
        try {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
        } catch (_: Exception) {
        }
    }

    private fun stopTunnel() {
        bootGen.incrementAndGet()
        running.set(false)
        dropUnderlying()
        WuadDnsServer.stop()
        MihomoEngine.stop()
        loop?.cancel()
        loop = null
        try {
            tun?.close()
        } catch (_: Exception) {
        }
        tun = null
    }

    private fun comboHint(): String {
        val bits = ArrayList<String>()
        if (Prefs.dnsOn) bits.add("DNS去广告")
        if (Prefs.routeOn) bits.add("分流")
        if (Prefs.skipOn) bits.add("GKD待命")
        return if (bits.isEmpty()) "隧道空转" else bits.joinToString(" + ")
    }

    private fun notify() = NotificationCompat.Builder(this, "vpn")
        .setContentTitle(getString(R.string.vpn_notify_title))
        .setContentText(getString(R.string.vpn_notify_text))
        .setSmallIcon(R.drawable.ic_notify)
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    companion object {
        const val ACTION_STOP = "app.wuad.STOP_VPN"
        const val ACTION_RELOAD = "app.wuad.RELOAD_VPN"
        val running = AtomicBoolean(false)

        fun start(ctx: Context) {
            val i = Intent(ctx, WuadVpnService::class.java)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, WuadVpnService::class.java).setAction(ACTION_STOP))
        }

        fun reload(ctx: Context) {
            if (!running.get()) return
            ctx.startService(Intent(ctx, WuadVpnService::class.java).setAction(ACTION_RELOAD))
        }

        fun restoreIfWanted(ctx: Context): Intent? {
            if (!Prefs.vpnWanted || running.get()) return null
            val prep = VpnService.prepare(ctx) ?: run {
                start(ctx)
                return null
            }
            return prep
        }
    }
}
