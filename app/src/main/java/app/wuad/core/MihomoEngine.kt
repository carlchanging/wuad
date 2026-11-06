package app.wuad.core

import android.content.Context
import android.os.Process
import app.wuad.vpn.WuadVpnService
import java.io.File

object MihomoEngine {
    @Volatile
    var pid: Int = -1
        private set

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var lastError: String = ""
        private set

    fun binary(ctx: Context): File = File(ctx.applicationInfo.nativeLibraryDir, "libmihomo.so")

    @Synchronized
    fun start(ctx: Context, fd: Int): Boolean {
        stop()
        lastError = ""
        val bin = binary(ctx)
        if (!bin.exists()) {
            lastError = "没找到 libmihomo.so"
            return false
        }
        RouteStore.init(ctx)
        RuleStore.init(ctx)
        SubStore.init(ctx)
        SubFetch.init(ctx)
        try { RouteUpdater.repairStored() } catch (_: Exception) {}
        try { SubFetch.ensureAll() } catch (_: Exception) {}
        val work = ClashConfig.workDir(ctx)
        try {
            seedPackagesXml(ctx)
        } catch (_: Exception) {
        }
        try {
            ClashConfig.write(ctx, fd)
        } catch (e: Exception) {
            lastError = "写配置失败：${e.message}"
            return false
        }
        val needGeo = Prefs.routeOn && !(ClashConfig.hasGeoSite(work) && ClashConfig.hasGeoIp(work))
        return try {
            MihomoNative.ensure()
            val p = MihomoNative.start(bin.absolutePath, work.absolutePath, fd)
            if (p <= 0) {
                lastError = "fork 失败"
                false
            } else {
                pid = p
                running = alive(p)
                if (!running) {
                    lastError = "进程瞬间退出，看 mihomo.log"
                    false
                } else {
                    waitApi()
                    if (!MihomoApi.alive()) {
                        lastError = "内核 API 没起来，多半端口被残留进程占了"
                        EventStore.add("sys", lastError, "看 mihomo.log")
                    }
                    try { MihomoApi.setMode(Prefs.mode) } catch (_: Exception) {}
                    try {
                        val keep = Prefs.node
                        if (keep.isNotBlank()) MihomoApi.select(keep)
                    } catch (_: Exception) {}
                    Thread.sleep(250)
                    val tunErr = tunStartError(work)
                    if (tunErr != null) {
                        lastError = tunErr
                        EventStore.add("sys", "TUN 没挂上，流量进黑洞", tunErr)
                    }
                    ConnWatcher.start(ctx)
                    if (needGeo) fetchGeoLater(ctx)
                    true
                }
            }
        } catch (e: Throwable) {
            lastError = "JNI：${e.message}"
            false
        }
    }

    @Synchronized
    fun stop() {
        ConnWatcher.stop()
        val p = pid
        try {
            MihomoNative.ensure()
            if (p > 1) MihomoNative.stop(p)
            killStrays()
            if (p > 1 && alive(p)) MihomoNative.kill(p)
            killStrays()
            Thread.sleep(80)
        } catch (_: Throwable) {
        }
        pid = -1
        running = false
    }

    fun log(ctx: Context): String {
        val f = File(ClashConfig.workDir(ctx), "mihomo.log")
        if (!f.exists()) return ""
        val text = f.readText()
        return if (text.length > 4000) text.takeLast(4000) else text
    }

    fun tunStartError(work: File): String? {
        val text = try { File(work, "mihomo.log").readText() } catch (_: Exception) { return null }
        return when {
            text.contains("packages.xml") || text.contains("p.xml") ||
                text.contains("build android rules") -> "读不到 packages.xml，TUN 没挂上"
            text.contains("Start TUN listening error") || text.contains("netlink socket") ->
                "Android 禁了 netlink，auto-detect 不能开"
            text.contains("address already in use") -> "内核端口被占，多开了实例"
            else -> null
        }
    }

    fun isMihomoCmd(cmdline: String): Boolean = cmdline.contains("libmihomo.so")

    private fun fetchGeoLater(ctx: Context) {
        Thread({
            try {
                val msg = GeoStore.ensure(ctx)
                if (msg != "geosite / geoip 已在本地" && running) {
                    EventStore.add("sys", "geo 资源已就绪，重载分流", msg)
                    WuadVpnService.reload(ctx)
                }
            } catch (e: Exception) {
                EventStore.add("sys", "geo 没下下来，GEOSITE/GEOIP 会缺席", e.message ?: "")
            }
        }, "geo-fetch").start()
    }

    private fun killStrays() {
        val self = Process.myPid()
        val dirs = File("/proc").listFiles() ?: return
        for (dir in dirs) {
            val p = dir.name.toIntOrNull() ?: continue
            if (p <= 1 || p == self) continue
            val cmd = try {
                String(File(dir, "cmdline").readBytes(), Charsets.ISO_8859_1)
            } catch (_: Exception) {
                continue
            }
            if (!isMihomoCmd(cmd)) continue
            try { MihomoNative.kill(p) } catch (_: Exception) {}
        }
    }

    private fun seedPackagesXml(ctx: Context) {
        val body = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?><packages></packages>"
        listOf(
            File("/data/data/app.wuad/p.xml"),
            File(ctx.applicationInfo.dataDir, "p.xml")
        ).distinctBy { it.absolutePath }.forEach { f ->
            try {
                f.parentFile?.mkdirs()
                f.writeText(body)
            } catch (_: Exception) {
            }
        }
    }

    private fun waitApi() {
        repeat(20) {
            if (MihomoApi.alive()) return
            Thread.sleep(150)
        }
    }

    private fun alive(p: Int): Boolean = p > 1 && File("/proc/$p").exists()
}
