package app.wuad.core

import android.content.Context
import android.provider.Settings
import app.wuad.dns.DnsCodec
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object DnsProbe {
    data class Sample(val host: String, val wantFake: Boolean, val note: String)

    val samples = listOf(
        Sample("www.google.com", true, "海外搜索"),
        Sample("www.gstatic.com", true, "测速同域"),
        Sample("www.youtube.com", true, "海外视频"),
        Sample("www.github.com", true, "开发站"),
        Sample("chatgpt.com", true, "ChatGPT"),
        Sample("api.openai.com", true, "OpenAI"),
        Sample("www.facebook.com", true, "海外社交"),
        Sample("x.com", true, "Twitter"),
        Sample("www.baidu.com", false, "国内搜索"),
        Sample("www.qq.com", false, "国内门户"),
        Sample("www.taobao.com", false, "国内电商"),
        Sample("weixin.qq.com", false, "微信")
    )

    fun run(ctx: Context): String {
        val mode = privateDns(ctx)
        EventStore.add("sys", "私人DNS：$mode", privateHint(mode))
        if (!MihomoEngine.running || !MihomoApi.alive()) {
            EventStore.add("sys", "诊断中止：内核没起来", MihomoEngine.lastError)
            return "内核没起来，先回首页开网络栈"
        }
        try {
            val cfg = MihomoApi.configs()
            val dns = cfg.optJSONObject("dns") ?: org.json.JSONObject()
            val tun = cfg.optJSONObject("tun") ?: org.json.JSONObject()
            EventStore.add(
                "sys",
                "内核 mode=${cfg.optString("mode")} dns=${dns.optString("enhanced-mode")} tun=${tun.optString("stack")}",
                "fake-ip-range=${dns.optString("fake-ip-range")} hijack=${tun.opt("dns-hijack")}"
            )
            EventStore.add(
                "sys",
                "1054 是内核直连，浏览器/微信走隧道 53",
                "诊断符合不代表 App 解析通。私人 DNS / Chrome 安全 DNS 仍会绕开。"
            )
        } catch (e: Exception) {
            EventStore.add("sys", "读不到内核配置", e.message ?: "")
        }
        val rows = samples.map { s ->
            try {
                val listen = listenQuery(s.host)
                val mapped = try { MihomoApi.dnsQuery(s.host, "A") } catch (_: Exception) { "" }
                val got = classify(listen)
                val ok = when {
                    listen.startsWith("Status=") || listen.contains("无记录") || listen.startsWith("失败") -> false
                    s.wantFake -> got.startsWith("FAKE-IP")
                    else -> got.startsWith("真IP")
                }
                val mark = if (ok) "符合" else "异常"
                EventStore.add(
                    "dns",
                    "探测 ${s.host} → $listen · $got · $mark",
                    "${s.note} · 期望${if (s.wantFake) "FAKE-IP" else "真IP"}" +
                        if (mapped.isNotBlank() && mapped != listen) " · API映射 $mapped" else ""
                )
                "${s.host} $got $mark"
            } catch (e: Exception) {
                EventStore.add("dns", "探测 ${s.host} 失败", e.message ?: "内核 DNS 接口没应答")
                "${s.host} 失败"
            }
        }
        return (listOf("私人DNS $mode") + rows).joinToString("\n")
    }

    fun listenQuery(host: String): String {
        val sock = DatagramSocket()
        try {
            sock.soTimeout = 4000
            val q = DnsCodec.queryA((1..65534).random(), host)
            sock.send(DatagramPacket(q, q.size, InetAddress.getByName("127.0.0.1"), 1054))
            val buf = ByteArray(512)
            val pack = DatagramPacket(buf, buf.size)
            sock.receive(pack)
            val ips = DnsCodec.aRecords(pack.data.copyOf(pack.length))
            return if (ips.isEmpty()) "无记录" else ips.joinToString(",")
        } catch (e: Exception) {
            return "失败:${e.message ?: "1054 没应答"}"
        } finally {
            sock.close()
        }
    }

    fun classify(ans: String): String {
        val first = ans.substringBefore(',').trim()
        return when {
            first.startsWith("198.18.") -> "FAKE-IP"
            first.startsWith("Status=") || first.startsWith("失败") || first.contains("无记录") || first.isEmpty() -> "无记录"
            else -> "真IP"
        }
    }

    fun privateDns(ctx: Context): String {
        return try {
            val mode = Settings.Global.getString(ctx.contentResolver, "private_dns_mode").orEmpty()
            val host = Settings.Global.getString(ctx.contentResolver, "private_dns_specifier").orEmpty()
            when (mode) {
                "", "off" -> "关"
                "opportunistic" -> "自动"
                "hostname" -> "指定 ${host.ifBlank { "?" }}"
                else -> mode
            }
        } catch (_: Exception) {
            "读不到"
        }
    }

    fun privateHint(mode: String): String {
        return if (mode == "关") {
            "系统会走 VPN DNS。浏览器仍可能自己开安全 DNS。"
        } else {
            "私人 DNS 开着时 mihomo 劫持不了 53 端口。浏览器会直接 ERR_NAME_NOT_RESOLVED，日志里也不会有连接。"
        }
    }
}
