package app.wuad.core

object StatAgg {
    fun dnsHost(title: String): String {
        val t = title.trim()
        return when {
            t.startsWith("REJECT ", true) -> t.substring(7).trim().substringBefore(' ')
            t.contains("REJECT", true) -> t.substringBefore(' ').trim()
            else -> t.substringBefore(' ').trim()
        }
    }

    fun clickWho(e: WuadEvent): String = e.sub.ifBlank { e.title }

    fun top(src: List<WuadEvent>, kind: String, since: Long, n: Int): List<Pair<String, Int>> {
        val keys = src.asSequence()
            .filter { it.kind == kind && it.t >= since }
            .map { if (kind == "dns") dnsHost(it.title) else clickWho(it) }
            .filter { it.isNotBlank() && !it.startsWith("上游") && it != "DNS" }
            .toList()
        return keys.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
            .take(n)
            .map { it.key to it.value }
    }

    fun digest(src: List<WuadEvent>, since: Long): String {
        val ads = top(src, "dns", since, 3)
        val lastAd = src.firstOrNull { it.kind == "dns" && it.t >= since && it.title.contains("REJECT", true) }
        val lastClick = src.firstOrNull { it.kind == "click" && it.t >= since }
        val bits = ArrayList<String>()
        if (ads.isNotEmpty()) bits.add("今日广告 Top：" + ads.joinToString(" · ") { "${it.first} ${it.second}" })
        if (lastAd != null) bits.add("最近拦截：${dnsHost(lastAd.title)}")
        if (lastClick != null) bits.add("最近跳过：${lastClick.title.removePrefix("点击 ").ifBlank { lastClick.sub }}")
        return if (bits.isEmpty()) "今天还没有拦截或跳过。开着 DNS / GKD 用一会儿就会堆在这儿。" else bits.joinToString("\n")
    }
}
