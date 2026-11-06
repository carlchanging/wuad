package app.wuad.core

import android.content.Context
import java.io.File

data class GeoPack(
    val key: String,
    val title: String,
    val purpose: String,
    val defaultUrl: String,
    val fileName: String
)

object GeoStore {
    /** 用户给的 FLClash 内置资源地址，原样预置。 */
    const val URL_GEOIP_META = "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geoip.metadb"
    const val URL_ASN = "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/GeoLite2-ASN.mmdb"
    const val URL_GEOIP_DAT = "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geoip.dat"
    const val URL_GEOSITE = "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geosite.dat"

    val packs = listOf(
        GeoPack(
            "geoip_meta",
            "geoip.metadb",
            "给 mihomo 做 GEOIP 国家/地区匹配，例如 GEOIP,CN,直连。",
            URL_GEOIP_META,
            "geoip.metadb"
        ),
        GeoPack(
            "asn",
            "GeoLite2-ASN.mmdb",
            "按 ASN/运营商分流，例如电信网段走直连。",
            URL_ASN,
            "GeoLite2-ASN.mmdb"
        ),
        GeoPack(
            "geoip_dat",
            "geoip.dat",
            "v2ray 格式 IP 库，和 geosite 一套，给分流规则用。",
            URL_GEOIP_DAT,
            "geoip.dat"
        ),
        GeoPack(
            "geosite",
            "geosite.dat",
            "域名分类库：google、youtube、cn、gfw。规则写成 GEOSITE,gfw,代理。",
            URL_GEOSITE,
            "geosite.dat"
        )
    )

    val required = packs.filter { it.key == "geosite" || it.key == "geoip_dat" }

    fun dir(ctx: Context) = File(ctx.filesDir, "geo").apply { mkdirs() }

    fun file(ctx: Context, pack: GeoPack) = File(dir(ctx), pack.fileName)

    fun packForUrl(url: String): GeoPack? {
        if (url.isBlank()) return null
        return packs.find { url.contains(it.fileName, true) }
    }

    fun mirrors(pack: GeoPack): List<String> {
        val custom = try { Prefs.geoUrl(pack.key, pack.defaultUrl) } catch (_: Exception) { pack.defaultUrl }
        val jsdelivr = "https://cdn.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@release/${pack.fileName}"
        val ghfast = "https://ghfast.top/${pack.defaultUrl}"
        return listOf(custom, pack.defaultUrl, jsdelivr, ghfast).filter { it.isNotBlank() }.distinct()
    }

    @Synchronized
    fun update(ctx: Context, pack: GeoPack): String {
        val dest = file(ctx, pack)
        var last: Exception? = null
        for (url in mirrors(pack)) {
            try {
                val n = RemoteFiles.download(url, dest, connectMs = 20000, readMs = 180000)
                Prefs.setLastUpdate(pack.key, System.currentTimeMillis())
                Prefs.setGeoUrl(pack.key, url)
                return "${pack.title} 已下载 ${n / 1024} KB"
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("没有可用的 geo 地址")
    }

    @Synchronized
    fun ensure(ctx: Context): String {
        val parts = ArrayList<String>()
        required.forEach { pack ->
            val local = file(ctx, pack)
            if (local.exists() && local.length() > 100 * 1024) return@forEach
            parts.add(update(ctx, pack))
        }
        return if (parts.isEmpty()) "geosite / geoip 已在本地" else parts.joinToString("\n")
    }

    fun deleteLocal(ctx: Context, pack: GeoPack) {
        file(ctx, pack).delete()
        Prefs.setLastUpdate(pack.key, 0)
    }
}
