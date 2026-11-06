package app.wuad.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.wuad.dns.WuadDnsServer
import java.io.File

object ClashConfig {
    const val TUN_ADDR = "172.19.0.1"
    const val TUN_DNS = "172.19.0.2"
    const val TUN_PREFIX = 30
    const val TUN_ADDR6 = "fdfe:dcba:9876::1"
    const val TUN_DNS6 = "fdfe:dcba:9876::2"
    const val TUN_PREFIX6 = 126
    /**
     * gvisor 直连 IPv6 在这台环境会 network is unreachable，豆包等国内 App 爱走 v6/QUIC，
     * 关隧道 IPv6 让它们回落 A 记录直连。不是给某个 App 开白名单。
     */
    const val TUN_IPV6 = false
    const val MIXED_PORT = 17890
    const val API = "127.0.0.1:19090"
    const val FAKE_IP_RANGE = "198.18.0.1/16"
    const val TUN_AUTO_DETECT = false

    /**
     * AdGuard DNS-only：系统解析器指向公网 v4/v6，隧道只路由这些地址。
     * 172.19.0.2 / fdfe::2 单独作兜底，避免被当成空对端。
     */
    val vpnDnsServers = listOf(
        "8.8.8.8", "8.8.4.4", "1.1.1.1", "1.0.0.1", TUN_DNS,
        "2001:4860:4860::8888", "2001:4860:4860::8844",
        "2606:4700:4700::1111", "2606:4700:4700::1001",
        TUN_DNS6
    )

    fun isIpv6(ip: String): Boolean = ':' in ip

    fun dnsRoutePrefix(ip: String): Int = if (isIpv6(ip)) 128 else 32

    fun dnsHijackLines(): List<String> {
        val out = ArrayList<String>()
        out += "any:53"
        out += "tcp://any:53"
        vpnDnsServers.forEach { ip ->
            val host = if (isIpv6(ip)) "[$ip]" else ip
            out += "$host:53"
            out += "tcp://$host:53"
        }
        return out.distinct()
    }

    fun underlyingIface(ctx: Context): String {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return ""
        val nets = try { cm.allNetworks } catch (_: Exception) { return "" }
        for (n in nets) {
            val caps = try { cm.getNetworkCapabilities(n) } catch (_: Exception) { null } ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            val ok = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!ok) continue
            val name = try { cm.getLinkProperties(n)?.interfaceName } catch (_: Exception) { null }
            if (!name.isNullOrBlank()) return name
        }
        return ""
    }

    fun processMode(): String {
        val need = try {
            Prefs.featureOff("route").any { it != "app.wuad" } ||
                Prefs.policies().any { it.key != "app.wuad" }
        } catch (_: Exception) {
            false
        }
        return if (need) "strict" else "off"
    }

    fun workDir(ctx: Context) = File(ctx.filesDir, "mihomo").apply { mkdirs() }

    fun write(ctx: Context, fd: Int): File {
        val work = workDir(ctx)
        File(work, "ruleset").mkdirs()
        File(work, "providers").mkdirs()
        copyGeo(ctx, work)
        copyRouteProviders(work)
        SubStore.all().forEach { SubFetch.touchEmpty(it.id) }
        val cfg = File(work, "config.yaml")
        cfg.writeText(build(work, fd, underlyingIface(ctx)))
        return cfg
    }

    fun build(work: File, fd: Int, iface: String = ""): String {
        val dnsOn = Prefs.dnsOn
        val routeOn = Prefs.routeOn
        val up = Prefs.dnsUpstream.ifBlank { "223.5.5.5" }
        val mode = Prefs.mode.ifBlank { "rule" }
        val providers = StringBuilder()
        val rules = ArrayList<String>()

        Prefs.featureOff("route").forEach { pkg ->
            if (pkg != "app.wuad") rules.add("- PROCESS-NAME,$pkg,DIRECT")
        }

        Prefs.policies().forEach { (pkg, pol) ->
            if (pkg == "app.wuad") return@forEach
            when (pol) {
                "direct" -> rules.add("- PROCESS-NAME,$pkg,DIRECT")
                "proxy" -> rules.add("- PROCESS-NAME,$pkg,PROXY")
            }
        }

        if (routeOn) {
            RouteStore.all().filter { it.enabled }.forEach { r ->
                when (r.type) {
                    "PROVIDER" -> {
                        val f = File(work, "ruleset/${r.id}.txt")
                        if (f.exists() && f.length() > 4) {
                            providers.append(
                                """
  ${r.id}:
    type: file
    behavior: domain
    format: text
    path: ./ruleset/${r.id}.txt
"""
                            )
                            rules.add("- RULE-SET,${r.id},${r.policy}")
                        }
                    }
                    "MATCH" -> rules.add("- MATCH,${r.policy}")
                    "GEOIP" -> if (hasGeoIp(work)) rules.add("- GEOIP,${r.payload},${r.policy}")
                    "GEOSITE" -> if (hasGeoSite(work)) rules.add("- GEOSITE,${r.payload},${r.policy}")
                    else -> rules.add("- ${r.type},${r.payload},${r.policy}")
                }
            }
        }

        if (rules.none { it.startsWith("- MATCH,") }) {
            rules.add(if (routeOn) "- MATCH,PROXY" else "- MATCH,DIRECT")
        }

        val proxyBlock = ClashBits.proxyYaml(SubStore.all().map { Triple(it.id, it.name, it.url) })
        val ruleProviders = if (providers.isNotEmpty()) "rule-providers:\n$providers" else ""
        val ns = if (dnsOn) "127.0.0.1:${WuadDnsServer.PORT}" else up
        val geoSite = hasGeoSite(work)
        val nsBlock = nameserverYaml(dnsOn, ns, up, geoSite)
        val ifaceLine = if (iface.isBlank()) "" else "interface-name: ${ClashBits.q(iface)}\n"

        return """
mixed-port: $MIXED_PORT
socks-port: 17891
allow-lan: false
bind-address: 127.0.0.1
mode: $mode
log-level: info
ipv6: $TUN_IPV6
external-controller: $API
secret: ""
find-process-mode: ${processMode()}
auto-detect-interface: $TUN_AUTO_DETECT
${ifaceLine}unified-delay: true
tcp-concurrent: true
geodata-mode: true
geo-auto-update: false

dns:
  enable: true
  listen: 0.0.0.0:1054
  ipv6: $TUN_IPV6
  enhanced-mode: fake-ip
  fake-ip-range: $FAKE_IP_RANGE
  fake-ip-filter-mode: blacklist
  use-hosts: true
  default-nameserver:
    - 223.5.5.5
    - 119.29.29.29
  proxy-server-nameserver:
    - 223.5.5.5
    - 119.29.29.29
$nsBlock
  fake-ip-filter:
${fakeIpFilterYaml(geoSite)}

tun:
  enable: true
  stack: gvisor
  auto-route: false
  auto-detect-interface: $TUN_AUTO_DETECT
  file-descriptor: $fd
  mtu: 1500
  inet4-address:
    - $TUN_ADDR/$TUN_PREFIX
${if (TUN_IPV6) "  inet6-address:\n    - $TUN_ADDR6/$TUN_PREFIX6\n" else ""}  dns-hijack:
${dnsHijackLines().joinToString("\n") { "    - ${ClashBits.q(it)}" }}

${if (routeOn || dnsOn) """
sniffer:
  enable: true
  override-destination: false
  parse-pure-ip: true
  force-dns-mapping: true
  sniff:
    HTTP:
      ports: [80, 8080]
    TLS:
      ports: [443, 8443]
    QUIC:
      ports: [443, 8443]
""" else ""}

$proxyBlock
$ruleProviders
rules:
${rules.joinToString("\n")}
""".trimStart()
    }

    fun hasGeoSite(work: File) = File(work, "geosite.dat").let { it.exists() && it.length() > 32 }

    fun hasGeoIp(work: File) =
        File(work, "geoip.metadb").let { it.exists() && it.length() > 32 } ||
            File(work, "geoip.dat").let { it.exists() && it.length() > 32 }

    fun fakeIpFilterLines(hasGeoSite: Boolean): List<String> {
        val out = ArrayList<String>()
        if (hasGeoSite) {
            out += "geosite:private"
            out += "geosite:cn"
            out += "geosite:category-ads-all"
        } else {
            out += "+.cn"
        }
        out += DnsSafe.fakeIpFilter
        return out.distinct()
    }

    fun fakeIpFilterYaml(hasGeoSite: Boolean): String =
        fakeIpFilterLines(hasGeoSite).joinToString("\n") { "    - ${ClashBits.q(it)}" }

    fun nameserverYaml(dnsOn: Boolean, ns: String, up: String, geoSite: Boolean): String {
        if (!dnsOn) {
            return """
  nameserver:
    - ${ClashBits.q(up)}
""".trimEnd()
        }
        val policy = ArrayList<String>()
        if (geoSite) {
            policy += "    ${ClashBits.q("geosite:cn")}: ${ClashBits.q(ns)}"
            policy += "    ${ClashBits.q("geosite:category-ads-all")}: ${ClashBits.q(ns)}"
        }
        if (!geoSite) policy += "    ${ClashBits.q("+.cn")}: ${ClashBits.q(ns)}"
        return """
  nameserver:
    - ${ClashBits.q("https://8.8.8.8/dns-query#PROXY")}
    - ${ClashBits.q("https://1.1.1.1/dns-query#PROXY")}
  nameserver-policy:
${policy.joinToString("\n")}
""".trimEnd()
    }

    private fun copyGeo(ctx: Context, work: File) {
        GeoStore.packs.forEach { pack ->
            val src = GeoStore.file(ctx, pack)
            if (src.exists() && src.length() > 32) {
                src.copyTo(File(work, pack.fileName), overwrite = true)
            }
        }
    }

    private fun copyRouteProviders(work: File) {
        RouteStore.all().filter { it.type == "PROVIDER" }.forEach { r ->
            val src = RouteStore.file(r.id)
            if (src.exists() && src.length() > 4) {
                src.copyTo(File(work, "ruleset/${r.id}.txt"), overwrite = true)
            }
        }
    }

}
