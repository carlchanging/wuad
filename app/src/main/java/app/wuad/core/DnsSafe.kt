package app.wuad.core

/**
 * 只放正式 mihomo / FLClash 那类 fake-ip 过滤，不给微信 QQ 飞书开小灶。
 * 国内域名靠 geosite:cn；没 geo 时退回 +.cn。
 * STUN / 局域网 / QQ ptlogin localhost 是官方默认，不是 IM 白名单。
 */
object DnsSafe {
    val fakeIpFilter = listOf(
        "*.lan",
        "*.local",
        "localhost.ptlogin2.qq.com",
        "localhost.ptlogin2.qq.com.cn",
        "+.stun.*.*",
        "stun.*.*",
        "time.*.com",
        "time.*.gov",
        "time.*.edu.cn",
        "ntp.*.com"
    )
}
