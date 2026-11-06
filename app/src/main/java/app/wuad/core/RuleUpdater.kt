package app.wuad.core

import android.content.Context
import java.io.File

object RuleUpdater {
    fun antiAdFile(ctx: Context) = File(ctx.filesDir, "anti-ad.txt")
    fun gkdFile(ctx: Context) = File(ctx.filesDir, "gkd-rules.json")

    fun trackFile(ctx: Context) = File(ctx.filesDir, "tracking.txt")

    fun updateAntiAd(ctx: Context): String {
        DnsSubStore.init(ctx)
        val enabled = DnsSubStore.enabled()
        if (enabled.isEmpty()) {
            Blocklist.load(ctx)
            return "没有启用的 DNS 订阅"
        }
        var ok = 0
        var fail = 0
        val notes = ArrayList<String>()
        enabled.forEach { sub ->
            try {
                val n = RemoteFiles.download(sub.url, DnsSubStore.file(ctx, sub.id))
                ok++
                notes += "${sub.name} ${n}B"
            } catch (e: Exception) {
                fail++
                notes += "${sub.name} 失败：${e.message}"
            }
        }
        Blocklist.load(ctx)
        Prefs.setLastUpdate("anti", System.currentTimeMillis())
        val head = "名单已更新 ${Blocklist.size.get()} 条（$ok 份成功" + if (fail > 0) "，$fail 份失败" else ""
        return head + "）· " + notes.joinToString("；")
    }

    fun updateGkd(ctx: Context): String {
        val raw = File(ctx.cacheDir, "gkd-raw.json5")
        val (url, _) = RemoteFiles.downloadFirst(Prefs.gkdFetchUrls(), raw)
        val compact = GkdParser.toCompact(raw.readText())
        gkdFile(ctx).writeText(compact)
        RuleStore.reloadGkdPreset()
        Prefs.setLastUpdate("gkd", System.currentTimeMillis())
        return "GKD 已更新 ${RuleStore.gkdPresetCount()} 条 · $url"
    }
}
