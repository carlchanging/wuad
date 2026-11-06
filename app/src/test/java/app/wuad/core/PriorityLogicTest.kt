package app.wuad.core

import app.wuad.dns.DnsCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PriorityLogicTest {
    @Test
    fun selector_sibling_and_child() {
        val sib = SelectorParse.parse("[id=\"ad\"] + [text=\"跳过\"]")
        assertEquals(2, sib.size)
        assertEquals(SelComb.DESC, sib[0].comb)
        assertEquals(SelComb.SIBLING, sib[1].comb)
        assertEquals("id" to "ad", sib[0].seg.attrs.first())
        assertEquals("text" to "跳过", sib[1].seg.attrs.first())

        val child = SelectorParse.parse("FrameLayout > @Button[text=\"跳过\"]")
        assertEquals(2, child.size)
        assertEquals(SelComb.CHILD, child[1].comb)
        assertTrue(child[1].seg.click)
        assertEquals("Button", child[1].seg.tag)
    }

    @Test
    fun selector_weibo_lite_splash() {
        val next = SelectorParse.parse("@[clickable=true][visibleToUser=true] + * > [text=\"跳过\"]")
        assertEquals(3, next.size)
        assertTrue(next[0].seg.click)
        assertTrue(next[0].seg.attrs.any { it == "clickable" to "true" })
        assertTrue(next[1].seg.any)
        assertEquals(SelComb.SIBLING, next[1].comb)
        assertEquals(SelComb.CHILD, next[2].comb)
        assertEquals("text" to "跳过", next[2].seg.attrs.first())

        val prev = SelectorParse.parse("@[clickable=true] - [text*=\"跳过\"][text.length<10]")
        assertEquals(2, prev.size)
        assertEquals(SelComb.PREV, prev[1].comb)
        assertTrue(prev[1].seg.attrs.any { it == "text*" to "跳过" })
        assertTrue(prev[1].seg.attrs.any { it.first == "text.length" && it.second == "<10" })
    }

    @Test
    fun selector_text_contains() {
        val one = SelectorParse.parse("[text*=\"跳过\"][clickable=true]")
        assertEquals(1, one.size)
        assertTrue(one[0].seg.attrs.any { it == "text*" to "跳过" })
        assertTrue(one[0].seg.attrs.any { it == "clickable" to "true" })
    }

    @Test
    fun dns_log_reject_and_type() {
        val a = DnsLogParse.parse("[DNS] ads.example.com A --> REJECT")
        assertNotNull(a)
        assertEquals("ads.example.com", a!!.host)
        assertEquals("A", a.qtype)
        assertEquals("REJECT", a.result)

        val b = DnsLogParse.parse("[TCP] www.google.com:443 --> PROXY")
        assertNotNull(b)
        assertEquals("www.google.com", b!!.host)
        assertEquals("TCP", b.qtype)

        val c = DnsLogParse.parse("""time="2026-11-03T15:43:07+08:00" level=info msg="[TCP] ads.doubleclick.net:443 --> reject"""")
        assertNotNull(c)
        assertEquals("ads.doubleclick.net", c!!.host)
        assertEquals("REJECT", c.result)

        val d = DnsLogParse.parse("""{"type":"info","payload":"[TCP] 198.18.0.23:443 --> REJECT"}""")
        assertNotNull(d)
        assertEquals("198.18.0.23", d!!.host)
        assertEquals("REJECT", d.result)

        assertTrue(DnsLogParse.parse("hello world") == null)
        val fail = DnsLogParse.parse("[DNS] www.google.com resolve failed")
        assertNotNull(fail)
        assertEquals("www.google.com", fail!!.host)
        assertEquals("FAIL", fail.result)

        val tcp = DnsLogParse.parse(
            """[TCP] 172.19.0.1:42108 --> www.google.com:443 match RuleSet(ls-proxy) using PROXY"""
        )
        assertNotNull(tcp)
        assertEquals("www.google.com", tcp!!.host)
        assertEquals("172.19.0.1", tcp.srcIp)
        assertEquals(42108, tcp.srcPort)
    }

    @Test
    fun conn_label_puts_app_first() {
        assertEquals("www.google.com TCP → PROXY", ConnLabel.title("", "www.google.com", "tcp", "PROXY"))
        assertEquals("微信 · www.google.com TCP → PROXY", ConnLabel.title("微信", "www.google.com", "tcp", "PROXY"))
        assertEquals("淘宝 · ads.example.com A → REJECT", ConnLabel.title("淘宝", "ads.example.com", "A", "REJECT"))
        assertEquals("ads.example.com A → REJECT", ConnLabel.title("", "ads.example.com", "A", "REJECT"))
        assertEquals("MATCH · :443", ConnLabel.sub("MATCH", "443"))
        assertEquals(listOf("119.147.19.76", "198.18.0.9"), ConnLabel.destCandidates("119.147.19.76", "198.18.0.9"))
        assertEquals(listOf("198.18.0.9"), ConnLabel.destCandidates("", "198.18.0.9"))
        assertEquals(emptyList<String>(), ConnLabel.destCandidates("127.0.0.1", ""))
        assertEquals("微信(分身)", ConnLabel.dualSuffix(99910287, "微信"))
        assertEquals("微信", ConnLabel.dualSuffix(10287, "微信"))
        assertTrue(ConnLabel.isFakeIp("198.18.0.9"))
        assertFalse(ConnLabel.isFakeIp("8.8.8.8"))
    }

    @Test
    fun click_tab_keeps_skip_and_clicks() {
        val boot = WuadEvent(1, "sys", "跳过引擎已接管", "启动")
        val tap = WuadEvent(2, "click", "点击 淘宝 · 开屏广告", "com.taobao.taobao")
        val dns = WuadEvent(3, "dns", "ads.example.com A → REJECT", "")
        assertFalse(EventStore.inClickTab(boot))
        assertTrue(EventStore.inClickTab(tap))
        assertFalse(EventStore.inClickTab(dns))
        assertTrue(EventStore.isClick(tap))
        assertFalse(EventStore.isClick(boot))
    }

    @Test
    fun event_persist_keeps_clicks() {
        val src = (1..120).map { i ->
            WuadEvent(i.toLong(), "conn", "host$i", "")
        } + listOf(
            WuadEvent(200, "click", "点击 淘宝 · 开屏广告", "com.taobao.taobao"),
            WuadEvent(201, "dns", "ads.example.com TCP → REJECT", "")
        )
        val kept = EventStore.pickPersist(src.sortedByDescending { it.t })
        assertTrue(kept.any { it.kind == "click" && it.title.contains("淘宝") })
        assertTrue(kept.any { it.kind == "dns" })
        assertTrue(kept.count { it.kind == "click" } >= 1)
    }

    @Test
    fun stat_agg_top_hosts() {
        assertEquals("ads.example.com", StatAgg.dnsHost("REJECT ads.example.com"))
        assertEquals("ads.example.com", StatAgg.dnsHost("ads.example.com TCP → REJECT"))
        val now = System.currentTimeMillis()
        val src = listOf(
            WuadEvent(now, "dns", "REJECT ads.a.com", ""),
            WuadEvent(now, "dns", "REJECT ads.a.com", ""),
            WuadEvent(now, "dns", "REJECT ads.b.com", ""),
            WuadEvent(now, "click", "点击 淘宝 · 开屏", "com.taobao.taobao")
        )
        val top = StatAgg.top(src, "dns", now - 1000, 5)
        assertEquals("ads.a.com", top.first().first)
        assertEquals(2, top.first().second)
        val dig = StatAgg.digest(src, now - 1000)
        assertTrue(dig.contains("ads.a.com"))
        assertTrue(dig.contains("淘宝"))
    }

    @Test
    fun proxy_delay_path_encodes_space() {
        val enc = MihomoApi.encodeProxy("[主力] 香港 01")
        assertFalse(enc.contains("+"))
        assertTrue(enc.contains("%20"))
        assertTrue(enc.contains("%5B") || enc.contains("["))
        assertEquals("GLOBAL", MihomoApi.activeGroup("global"))
        assertEquals("PROXY", MihomoApi.activeGroup("rule"))
        assertEquals("PROXY", MihomoApi.activeGroup("direct"))
        val q = java.net.URLEncoder.encode("https://www.gstatic.com/generate_204", "UTF-8")
        assertTrue(q.contains("%3A"))
        assertFalse(q.contains("://"))
    }

    @Test
    fun hostlist_parse_hosts_and_adblock() {
        assertEquals("ads.example.com", HostlistParse.host("ads.example.com"))
        assertEquals("tracker.com", HostlistParse.host("||tracker.com^"))
        assertEquals("foo.bar", HostlistParse.host("0.0.0.0 foo.bar"))
        assertTrue(HostlistParse.host("@@||good.com^") == null)
        assertTrue(HostlistParse.host("! comment") == null)
        assertEquals("dns.weixin.qq.com", HostlistParse.host("||dns.weixin.qq.com^"))
    }

    @Test
    fun dns_subs_keep_official_lists() {
        val factory = DnsSubStore.factory()
        assertTrue(factory.any { it.id == DnsSubStore.ANTI_AD && it.enabled && it.url.contains("anti-ad.net") })
        assertTrue(factory.any { it.id == DnsSubStore.TRACK && !it.enabled })
        val rules = factory.find { it.id == DnsSubStore.ADRULES }
        assertNotNull(rules)
        assertFalse(rules!!.enabled)
        assertEquals(DnsSubStore.ADRULES_URL, rules.url)
        assertNull(DnsSubStore.normalize("1.1.1.1"))
        assertEquals(DnsSubStore.ADRULES_URL, DnsSubStore.normalize(DnsSubStore.ADRULES_URL))
    }

    @Test
    fun dns_safe_follows_flclash_filter() {
        assertTrue(DnsSafe.fakeIpFilter.contains("*.lan"))
        assertTrue(DnsSafe.fakeIpFilter.contains("localhost.ptlogin2.qq.com"))
        assertTrue(DnsSafe.fakeIpFilter.any { it.contains("stun") })
        assertFalse(DnsSafe.fakeIpFilter.any { it.contains("feishu") })
        assertFalse(DnsSafe.fakeIpFilter.any { it.contains("wechat.com") })
        assertFalse(DnsSafe.fakeIpFilter.any { it == "+.qq.com" })
    }

    @Test
    fun traffic_fmt_compact() {
        assertEquals("0 B", TrafficFmt.human(0))
        assertEquals("512 B", TrafficFmt.human(512))
        assertEquals("1.5 KB", TrafficFmt.human(1536))
        assertEquals("12 KB", TrafficFmt.human(12L * 1024))
        assertEquals("3.4 MB", TrafficFmt.human((3.4 * 1024 * 1024).toLong()))
        assertEquals("2.0 GB", TrafficFmt.human(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun dns_normalize() {
        assertEquals("1.1.1.1", DnsUpStore.normalize("1.1.1.1"))
        assertEquals("https://1.1.1.1/dns-query", DnsUpStore.normalize("https://1.1.1.1/dns-query"))
        assertEquals("tls://1.1.1.1", DnsUpStore.normalize(" tls://1.1.1.1 "))
        assertEquals("quic://dns.adguard.com", DnsUpStore.normalize("quic://dns.adguard.com"))
        assertTrue(DnsUpStore.normalize("not a dns") == null)
        assertTrue(DnsUpStore.normalize("") == null)
        assertEquals("1.1.1.1", DnsUpStore.udpHost("1.1.1.1"))
        assertEquals("1.1.1.1", DnsUpStore.udpHost("https://1.1.1.1/dns-query"))
        assertEquals("dns.alidns.com", DnsUpStore.udpHost("https://dns.alidns.com/dns-query"))
        assertEquals("1.1.1.1", DnsUpStore.udpHost("tls://1.1.1.1"))
        assertEquals("223.5.5.5", DnsUpStore.udpHost(""))
        val ups = DnsUpStore.factory()
        assertEquals(DnsUpStore.ALI, ups.first().id)
        assertEquals(DnsUpStore.ALI_URL, ups.first().url)
        assertEquals(3, ups.size)
        assertTrue(ups.any { it.id == "plain-cf" })
        assertFalse(ups.any { it.id == "plain-google" || it.id == "doh-google" || it.id == "doh-cf" || it.id == "dot-cf" })
    }

    @Test
    fun dns_sinkhole_like_adguard() {
        val q = DnsCodec.queryA(0x1234, "ads.example.com")
        assertEquals("ads.example.com", DnsCodec.readQueryName(q))
        assertEquals(1, DnsCodec.qtype(q))
        val r = DnsCodec.sinkhole(q)
        assertEquals(0, r[3].toInt() and 0x0f)
        assertEquals(1, DnsCodec.u16(r, 6))
        assertEquals(0, r[r.size - 4].toInt() and 0xff)
        assertEquals(0, r[r.size - 1].toInt() and 0xff)
        val fail = DnsCodec.nxdomain(q)
        assertEquals(3, fail[3].toInt() and 0x0f)
        val sink = DnsCodec.sinkhole(q)
        assertEquals(listOf("0.0.0.0"), DnsCodec.aRecords(sink))
        val aaaa = DnsCodec.queryAAAA(0x1234, "ads.example.com")
        assertEquals(28, DnsCodec.qtype(aaaa))
        val hole6 = DnsCodec.sinkhole(aaaa)
        assertEquals(1, DnsCodec.u16(hole6, 6))
        assertEquals(16, DnsCodec.u16(hole6, hole6.size - 18))
        assertTrue(hole6.takeLast(16).all { it == 0.toByte() })
    }

    @Test
    fun fake_ip_filter_quotes_geosite_and_covers_wechat() {
        val yaml = ClashConfig.fakeIpFilterYaml(true)
        assertTrue(yaml.contains("\"geosite:cn\""))
        assertTrue(yaml.contains("\"geosite:private\""))
        assertTrue(yaml.contains("\"geosite:category-ads-all\""))
        assertFalse(yaml.contains("\"+.qq.com\""))
        assertFalse(yaml.contains("\"+.baidu.com\""))
        assertTrue(yaml.contains("localhost.ptlogin2.qq.com"))
        assertTrue(ClashConfig.fakeIpFilterYaml(false).contains("\"+.cn\""))
        assertFalse(ClashConfig.fakeIpFilterYaml(false).contains("geosite:cn"))
        val lines = ClashConfig.fakeIpFilterLines(true)
        assertEquals(lines.size, lines.distinct().size)
        val ns = ClashConfig.nameserverYaml(true, "127.0.0.1:1053", "1.1.1.1", true)
        assertTrue(ns.contains("nameserver-policy"))
        assertTrue(ns.contains("127.0.0.1:1053"))
        assertTrue(ns.contains("https://8.8.8.8/dns-query#PROXY"))
        assertTrue(ns.contains("https://1.1.1.1/dns-query#PROXY"))
        assertTrue(ns.contains("geosite:cn"))
        assertFalse(ns.contains("+.qq.com"))
        assertFalse(ns.contains("223.5.5.5"))
        assertFalse(ClashConfig.nameserverYaml(false, "127.0.0.1:1053", "1.1.1.1", true).contains("nameserver-policy"))
        assertTrue(ClashConfig.nameserverYaml(false, "127.0.0.1:1053", "1.1.1.1", true).contains("1.1.1.1"))
        assertTrue(ClashConfig.dnsHijackLines().contains("tcp://any:53"))
        assertTrue(ClashConfig.dnsHijackLines().contains("8.8.8.8:53"))
        assertTrue(ClashConfig.dnsHijackLines().contains("tcp://[2001:4860:4860::8888]:53"))
        assertTrue(ClashConfig.vpnDnsServers.contains(ClashConfig.TUN_DNS))
        assertTrue(ClashConfig.vpnDnsServers.contains(ClashConfig.TUN_DNS6))
        assertTrue(ClashConfig.vpnDnsServers.contains("2001:4860:4860::8888"))
        assertEquals(128, ClashConfig.dnsRoutePrefix(ClashConfig.TUN_DNS6))
        assertEquals(32, ClashConfig.dnsRoutePrefix("1.1.1.1"))
        assertEquals("off", ClashConfig.processMode())
        assertFalse(ClashConfig.TUN_AUTO_DETECT)
        assertFalse(ClashConfig.TUN_IPV6)
    }

    @Test
    fun tun_start_error_detects_netlink_ban() {
        val dir = kotlin.io.path.createTempDirectory("mihomo-work").toFile()
        try {
            assertNull(MihomoEngine.tunStartError(dir))
            java.io.File(dir, "mihomo.log").writeText(
                "Start TUN listening error: create NetworkUpdateMonitor: netlink socket in Android is banned by Google"
            )
            assertEquals("Android 禁了 netlink，auto-detect 不能开", MihomoEngine.tunStartError(dir))
            java.io.File(dir, "mihomo.log").writeText(
                "Start TUN listening error: build android rules: read packages list: open /data/system/packages.xml: permission denied"
            )
            assertEquals("读不到 packages.xml，TUN 没挂上", MihomoEngine.tunStartError(dir))
            java.io.File(dir, "mihomo.log").writeText(
                "External controller listen error: listen tcp 127.0.0.1:19090: bind: address already in use"
            )
            assertEquals("内核端口被占，多开了实例", MihomoEngine.tunStartError(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun mihomo_cmd_detects_forked_binary() {
        assertTrue(MihomoEngine.isMihomoCmd("/data/app/app.wuad/lib/arm64/libmihomo.so\u0000-d\u0000/data/data/app.wuad/files/mihomo"))
        assertFalse(MihomoEngine.isMihomoCmd("app.wuad"))
        assertFalse(MihomoEngine.isMihomoCmd(""))
    }

    @Test
    fun route_factory_puts_geosite_before_geoip() {
        val ids = RouteStore.factory().map { it.id }
        assertEquals(
            listOf("ls-reject", "ls-proxy", "ls-direct", "site-gfw", "site-cn", "geo-private", "geo-cn", "match"),
            ids
        )
        assertTrue(ids.indexOf("site-gfw") < ids.indexOf("geo-cn"))
        assertFalse(RouteStore.factory().first { it.id == "ls-reject" }.enabled)
        val scrambled = listOf(
            RouteStore.factory().first { it.id == "geo-cn" },
            RouteStore.factory().first { it.id == "match" },
            RouteStore.factory().first { it.id == "site-gfw" },
            RouteRule("r-custom", "DOMAIN", "example.com", "PROXY", "", true, false)
        )
        assertEquals(
            listOf("site-gfw", "geo-cn", "r-custom", "match"),
            RouteStore.ordered(scrambled).map { it.id }
        )
        val fac = RouteStore.factory().associateBy { it.id }
        assertEquals(GeoStore.URL_GEOSITE, fac.getValue("site-gfw").url)
        assertEquals(GeoStore.URL_GEOSITE, fac.getValue("site-cn").url)
        assertEquals(GeoStore.URL_GEOIP_DAT, fac.getValue("geo-private").url)
        assertEquals(GeoStore.URL_GEOIP_DAT, fac.getValue("geo-cn").url)
        assertEquals(
            "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geosite.dat",
            GeoStore.URL_GEOSITE
        )
        assertEquals(
            "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geoip.dat",
            GeoStore.URL_GEOIP_DAT
        )
        assertEquals("geosite", GeoStore.packForUrl(GeoStore.URL_GEOSITE)?.key)
        assertEquals("geoip_dat", GeoStore.packForUrl(GeoStore.URL_GEOIP_DAT)?.key)
    }

    @Test
    fun loyalsoldier_keeps_plus_dot_suffix() {
        assertEquals("+.google.com", RouteUpdater.plusDot("google.com"))
        assertEquals("+.youtube.com", RouteUpdater.plusDot("+.youtube.com"))
        assertEquals("+.facebook.com", RouteUpdater.plusDot(".facebook.com"))
        assertEquals("+.github.com", RouteUpdater.plusDot("GITHUB.COM"))
        assertEquals("1.2.3.4", RouteUpdater.plusDot("1.2.3.4"))
        val hosts = RouteUpdater.parsePayload(
            """
            payload:
              - +.google.com
              - '+.youtube.com'
              - baidu.com
              - www.github.com
            """.trimIndent()
        )
        assertTrue(hosts.contains("+.google.com"))
        assertTrue(hosts.contains("+.youtube.com"))
        assertTrue(hosts.contains("+.baidu.com"))
        assertTrue(hosts.contains("+.www.github.com"))
        assertFalse(hosts.contains("google.com"))
        assertFalse(hosts.contains("baidu.com"))
        assertEquals("FAKE-IP", DnsProbe.classify("198.18.0.23"))
        assertEquals("真IP", DnsProbe.classify("1.2.3.4"))
        assertEquals("无记录", DnsProbe.classify("失败:1054 没应答"))
        assertTrue(DnsProbe.samples.any { it.host == "chatgpt.com" && it.wantFake })
        assertTrue(DnsProbe.samples.any { it.host == "www.baidu.com" && !it.wantFake })
    }

    @Test
    fun mihomo_shell_proxies_are_not_nodes() {
        assertTrue(MihomoApi.isShell("COMPATIBLE", "Compatible"))
        assertTrue(MihomoApi.isShell("REJECT", "Reject"))
        assertTrue(MihomoApi.isShell("PROXY", "Selector"))
        assertTrue(MihomoApi.isShell("AUTO", "URLTest"))
        assertFalse(MihomoApi.isShell("香港01", "ss"))
        assertFalse(MihomoApi.isShell("[主力] 东京", "vmess"))
    }

    @Test
    fun clash_multi_sub_has_auto_group() {
        val empty = ClashBits.proxyYaml(emptyList())
        assertTrue(empty.contains("PROXY"))
        assertFalse(empty.contains("AUTO"))
        assertFalse(empty.contains("proxy-providers"))

        val yaml = ClashBits.proxyYaml(
            listOf(
                Triple("s-a", "主力", "https://a.example/clash"),
                Triple("s-b", "备用", "https://b.example/clash")
            )
        )
        assertTrue(yaml.contains("name: AUTO"))
        assertTrue(yaml.contains("type: url-test"))
        assertTrue(yaml.contains("- s-a"))
        assertTrue(yaml.contains("- s-b"))
        assertTrue(yaml.contains("[主力]"))
        assertTrue(yaml.contains("type: file"))
        assertTrue(yaml.contains("./providers/s-a.yaml"))
        assertTrue(yaml.contains("- AUTO"))
        assertFalse(yaml.contains("type: http"))
        assertFalse(yaml.contains("https://a.example/clash"))
    }

    @Test
    fun sub_fetch_extracts_clash_profile_and_share_links() {
        val profile = """
            mixed-port: 7890
            proxies:
              - {name: 香港01, type: ss, server: 1.2.3.4, port: 443}
              - name: 东京02
                type: vmess
            proxy-groups:
              - name: PROXY
                type: select
                proxies: [香港01, 东京02]
            rules:
              - MATCH,PROXY
        """.trimIndent()
        val extracted = SubFetch.extractProxies(profile)!!
        assertTrue(extracted.startsWith("proxies:"))
        assertFalse(extracted.contains("proxy-groups"))
        assertEquals(2, SubFetch.countNodes(extracted))

        val b64 = java.util.Base64.getEncoder().encodeToString(
            "vmess://abcd0123456789\nss://efgh0123456789\n".toByteArray()
        )
        val norm = SubFetch.normalize(b64.toByteArray())
        assertTrue(SubFetch.looksShareLinks(norm))
        assertEquals(2, SubFetch.countNodes(norm))
        assertEquals(0, SubFetch.countNodes("DIRECT\nREJECT\nCOMPATIBLE\n"))

        val parsed = SubFetch.parseNodes(extracted, "主力")
        assertEquals(2, parsed.size)
        assertEquals("[主力] 香港01", parsed[0].name)
        assertEquals("ss", parsed[0].type)
        assertEquals("[主力] 东京02", parsed[1].name)
        assertEquals("vmess", parsed[1].type)
        assertTrue(SubFetch.parseNodes("proxies:\n  - name: DIRECT\n    type: Direct\n").isEmpty())
    }

    @Test
    fun gkd_parser_keeps_selector_and_delay() {
        val raw = """
            {"apps":[{"id":"com.demo","name":"演示","groups":[{
              "key":0,"name":"开屏广告","rules":[{
                "matches":"[id='ad'] + [text='跳过']",
                "actionDelay":300,
                "activityIds":"com.demo.Splash"
              }]
            }]}]}
        """.trimIndent()
        val compact = GkdParser.toCompact(raw)
        assertTrue(compact.contains("[id='ad'] + [text='跳过']") || compact.contains("[id=\\\"ad\\\"]"))
        assertTrue(compact.contains("\"delay\":300"))
        assertTrue(compact.contains("开屏广告"))
        assertTrue(compact.contains("com.demo.Splash"))
    }

    @Test
    fun gkd_parser_keeps_any_matches() {
        val raw = """
            {"apps":[{"id":"com.weico.international","name":"微博轻享版","groups":[{
              "key":-1,"name":"开屏广告","rules":[{
                "anyMatches":[
                  "@[clickable=true] + ViewGroup > [text='跳过']",
                  "@[clickable=true] - [text*='跳过'][text.length<10]"
                ]
              }]
            }]}]}
        """.trimIndent()
        val compact = GkdParser.toCompact(raw)
        assertTrue(compact.contains("ViewGroup") || compact.contains("clickable=true"))
        assertTrue(compact.contains("weico.international"))
        assertFalse(compact.contains("vid*=\\\"close\\\""))
    }

    @Test
    fun gkd_parser_imports_rule_fields_as_is() {
        val raw = """
            {"version":1,"apps":[{"id":"com.autonavi.minimap","name":"高德地图","groups":[{
              "key":-1,"name":"开屏广告","matchTime":10000,"actionMaximum":1,"resetMatch":"app",
              "rules":[{"matches":"[text*='跳过'][text.length<10][width<500 && height<300][visibleToUser=true]"}]
            }]}],"globalGroups":[{
              "key":0,"name":"开屏广告-全局","matchTime":10000,"actionMaximum":2,"resetMatch":"app",
              "disableIfAppGroupMatch":"开屏广告",
              "rules":[{"anyMatches":["[text*='跳过'][text.length<10][visibleToUser=true]"],
                "excludeMatches":"[text*='搜索'][visibleToUser=true]"}]
            }]}
        """.trimIndent()
        val compact = GkdParser.toCompact(raw)
        val root = org.json.JSONObject(compact)
        assertEquals(4, root.getInt("format"))
        val rules = root.getJSONArray("rules")
        val gd = rules.getJSONObject(0)
        assertEquals("com.autonavi.minimap", gd.getString("pkg"))
        assertTrue(gd.getString("match").contains("跳过"))
        assertTrue(gd.getString("match").contains("text.length"))
        assertEquals(10000, gd.getInt("matchTime"))
        assertEquals(1, gd.getInt("actionMaximum"))
        assertEquals("app", gd.getString("resetMatch"))
        assertEquals("", gd.optString("excludeMatch"))
        val global = rules.getJSONObject(1)
        assertEquals("*", global.getString("pkg"))
        assertEquals("开屏广告", global.getString("disableIfGroup"))
        assertTrue(global.getString("excludeMatch").contains("搜索"))
        assertFalse(global.getBoolean("matchLauncher"))
        assertFalse(global.getBoolean("matchSystemApp"))
    }

    @Test
    fun gkd_groups_by_app() {
        val items = listOf(
            GkdItem("a:0", "com.weico.international", "微博轻享版", "开屏广告", "跳过", true, true),
            GkdItem("a:1", "com.weico.international", "微博轻享版", "分段广告-信息流", "x", true, false),
            GkdItem("b:0", "com.sina.weibo", "微博", "开屏广告", "跳过", true, true),
            GkdItem("*:0", "*", "全局", "开屏广告-全局", "跳过", true, true)
        )
        val apps = GkdGroups.of(items)
        assertEquals(3, apps.size)
        assertEquals("*", apps.first().pkg)
        val lite = apps.find { it.pkg == "com.weico.international" }!!
        assertEquals("微博轻享版", lite.name)
        assertEquals(2, lite.total)
        assertEquals(1, lite.onCount)
        assertTrue(lite.splashOn)
        assertEquals("开屏广告", lite.items.first().group)

        val q = GkdGroups.of(items, "轻享")
        assertEquals(1, q.size)
        assertEquals("com.weico.international", q.first().pkg)

        val inner = GkdGroups.ofPkg(items, "com.weico.international", "开屏")
        assertEquals(1, inner.size)
    }

    @Test
    fun splash_hunt_and_vid_star() {
        assertTrue(SplashHunt.textHit("跳过广告"))
        assertTrue(SplashHunt.textHit("跳过 3"))
        assertFalse(SplashHunt.textHit("登录"))
        assertTrue(SplashHunt.vidHit("com.taobao.taobao:id/splash_close"))
        assertTrue(SplashHunt.vidHit("com.jd.lib.splash:id/tv_skip"))
        assertFalse(SplashHunt.vidHit("com.taobao.taobao:id/tab_cart"))
        assertTrue(SplashHunt.looksSplashAct("com.taobao.tao.welcome.Welcome"))
        assertTrue(SplashHunt.looksSplashAct("com.bytedance.sdk.openadsdk.stub.activity.Stub_Standard_Activity"))
        assertFalse(SplashHunt.looksSplashAct("com.jingdong.app.mall.MainFrameActivity"))
        assertTrue(SplashHunt.looksMainAct("com.jingdong.app.mall.MainFrameActivity"))
        assertTrue(SplashHunt.looksMainAct("com.xxx.MainActivity"))
        assertFalse(SplashHunt.looksSplashAct("com.xxx.MainActivity"))

        val vid = SelectorParse.parse("@[vid*=\"close\"] > [text*=\"跳过\"][text.length<10]")
        assertEquals(2, vid.size)
        assertTrue(vid[0].seg.click)
        assertTrue(vid[0].seg.attrs.any { it == "vid*" to "close" })
        assertEquals(SelComb.CHILD, vid[1].comb)
        assertEquals(
            "[text*=\"跳过\"][text.length<10][width<500 && height<300][visibleToUser=true]",
            GkdParser.splashBundle("跳过")
        )
    }

    @Test
    fun json5_lite_quotes_keys() {
        val json = Json5Lite.toJson("{id:1,name:'开屏',enable:true}")
        val o = org.json.JSONObject(json)
        assertEquals(1, o.getInt("id"))
        assertEquals("开屏", o.getString("name"))
        assertTrue(o.getBoolean("enable"))
    }

    @Test
    fun unsupported_selector_is_dropped() {
        assertTrue(SelectorParse.isSupported("[text*=\"跳过\"][text.length<10][width<500 && height<300][visibleToUser=true]"))
        assertTrue(SelectorParse.isSupported("TextView[text^=\"跳过\"][text.length<=10][!(vid=\"title\")]"))
        assertTrue(SelectorParse.isSupported("[childCount=0][(text.length<10 && (text*=\"跳过\"))]"))
        assertTrue(SelectorParse.isSupported("@View[clickable=true] +(1,2) TextView"))
        assertTrue(SelectorParse.isSupported("ImageView <<n [vid=\"x\"]"))
        assertFalse(SelectorParse.isSupported("A ->1 B > C"))
        assertFalse(SelectorParse.isSupported("[width=getPrev(1).width]"))
        val notVid = SelectorParse.parse("TextView[!(vid=\"title\")]")
        assertEquals("vid!", notVid[0].seg.attrs.first().first)
        assertEquals("title", notVid[0].seg.attrs.first().second)
        val nth = SelectorParse.path("@View +(1,2) TextView")
        assertEquals(2, nth.segs.size)
        assertEquals(RelOp.PLUS, nth.rels[0].op)
        assertTrue(nth.rels[0].nth.hit(1) && nth.rels[0].nth.hit(2))
    }

    @Test
    fun gkd_global_defaults_skip_launcher_and_system() {
        val raw = """
            {"globalGroups":[{"key":0,"name":"开屏广告-全局","disableIfAppGroupMatch":"开屏广告","apps":[{"id":"com.tencent.mm","enable":false},{"id":"com.android.chrome","enable":true}],"rules":[{"matches":"[text*='跳过']"}]}]}
        """.trimIndent()
        val compact = GkdParser.toCompact(raw)
        val g = org.json.JSONObject(compact).getJSONArray("rules").getJSONObject(0)
        assertEquals("*", g.getString("pkg"))
        assertTrue(g.getBoolean("matchAnyApp"))
        assertFalse(g.getBoolean("matchLauncher"))
        assertFalse(g.getBoolean("matchSystemApp"))
        assertTrue(g.getString("denyApps").contains("com.tencent.mm"))
        assertTrue(g.getString("allowApps").contains("com.android.chrome"))
        val item = GkdItem("*:0", "*", "全局", "开屏广告-全局", "[text*='跳过']", true, true)
        assertTrue(GkdScope.allow(item, "com.autonavi.minimap", launcher = false, system = false))
        assertFalse(GkdScope.allow(item, "com.android.launcher", launcher = true, system = true))
        assertFalse(GkdScope.allow(item, "com.oplus.systemui", launcher = false, system = true))
        val appRule = GkdItem("a:0", "com.autonavi.minimap", "高德", "开屏广告", "[text*='跳过']", true, true)
        assertTrue(GkdScope.allow(appRule, "com.autonavi.minimap", launcher = false, system = false))
        assertFalse(GkdScope.allow(appRule, "com.android.launcher", launcher = true, system = true))
        val denied = GkdItem(
            "*:0", "*", "全局", "开屏广告-全局", "[text*='跳过']", true, true,
            denyApps = "com.tencent.mm,com.eg.android.AlipayGphone"
        )
        assertFalse(GkdScope.allow(denied, "com.tencent.mm", launcher = false, system = false))
        assertTrue(GkdScope.allow(denied, "com.tencent.mobileqq", launcher = false, system = false))
        assertFalse(GkdScope.allow(denied, "com.android.systemui", launcher = false, system = true))
    }

    @Test
    fun selector_or_inside_brackets_stays_one() {
        val parts = SelectorParse.splitOr("[text*=\"跳过\" || text*=\"Skip\"][visibleToUser=true]")
        assertEquals(1, parts.size)
        val seg = SelectorParse.parse(parts[0])
        assertEquals(1, seg.size)
        assertTrue(seg[0].seg.clauses.size >= 1)
    }

    @Test
    fun gkd_parser_splits_group_rules() {
        val raw = """
            {"apps":[{"id":"com.demo","name":"Demo","groups":[{
              "key":2,"name":"分段广告","rules":[
                {"key":0,"matches":"[text='广告']"},
                {"key":1,"preKeys":[0],"matches":"[text='关闭']"}
              ]
            }]}]}
        """.trimIndent()
        val compact = GkdParser.toCompact(raw)
        val rules = org.json.JSONObject(compact).getJSONArray("rules")
        assertEquals(2, rules.length())
        assertEquals("com.demo:2:0", rules.getJSONObject(0).getString("id"))
        assertEquals("com.demo:2:1", rules.getJSONObject(1).getString("id"))
        assertEquals("0", rules.getJSONObject(1).getString("preKeys"))
        assertEquals(1, rules.getJSONObject(1).getInt("ruleKey"))
    }

    @Test
    fun gkd_mirrors_keep_lin_arm() {
        assertTrue(Prefs.GKD_DEFAULT_URL.contains("Lin-arm"))
        assertTrue(Prefs.GKD_MIRRORS.any { it.contains("ghfast") || it.contains("gitmirror") })
        assertTrue(Prefs.GKD_LEGACY_URL.contains("jsdelivr"))
    }

    @Test
    fun gkd_cats_official_prefix() {
        assertEquals(11, GkdCats.all.size)
        assertTrue(GkdCats.all[0].defaultOn)
        assertTrue(GkdCats.all.drop(1).all { !it.defaultOn })
        assertEquals("开屏广告", GkdCats.of("开屏广告-全局").name)
        assertEquals("全屏广告", GkdCats.of("全屏广告-弹窗广告").name)
        assertEquals("局部广告", GkdCats.of("局部广告").name)
        assertEquals("分段广告", GkdCats.of("分段广告-信息流").name)
        assertEquals("权限提示", GkdCats.of("权限提示-通知权限").name)
        assertEquals("功能类", GkdCats.of("功能类-签到").name)
        assertEquals("其他", GkdCats.of("自定义").name)
        assertEquals("其他", GkdCats.of("").name)
    }
}
