package app.wuad.core

object SplashHunt {
    private val skipBits = listOf("跳过", "跳過", "跳過", "skip")
    private val banBits = listOf("登录", "購物", "购物", "消息", "搜索", "设置", "首页", "我的", "客服", "领取")

    fun textHit(raw: String): Boolean {
        val t = raw.trim()
        if (t.length !in 1..16) return false
        if (banBits.any { t.contains(it) }) return false
        return skipBits.any { t.contains(it, true) }
    }

    fun vidHit(vid: String): Boolean {
        if (vid.isEmpty()) return false
        val v = vid.lowercase()
        if (listOf("cart", "search", "message", "setting", "tab").any { it in v }) return false
        val skip = v.contains("skip") && !v.contains("skip_login")
        val splashClose = v.contains("close") && (v.contains("splash") || v.contains("ad") || v.contains("skip"))
        return skip || splashClose
    }

    fun looksSplashAct(cls: String): Boolean {
        if (cls.isEmpty()) return false
        val c = cls.lowercase()
        if (looksMainAct(c)) return false
        return listOf("splash", "welcome", "advert", "adsplash", "openadsdk", "stub_standard", "splashad")
            .any { it in c }
    }

    fun looksMainAct(cls: String): Boolean {
        if (cls.isEmpty()) return false
        val c = cls.lowercase()
        return listOf("mainactivity", "mainframe", "homeactivity", "homepage", "launcher", "tabactivity", "main_activity")
            .any { it in c }
    }

    fun labelOf(pkg: String): String = when (pkg) {
        "com.taobao.taobao" -> "淘宝"
        "com.jingdong.app.mall" -> "京东"
        "com.weico.international" -> "微博轻享版"
        "com.sina.weibo" -> "微博"
        else -> pkg.substringAfterLast('.')
    }
}
