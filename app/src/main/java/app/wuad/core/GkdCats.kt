package app.wuad.core

/**
 * 正式 GKD 订阅根上的 categories。组名以分类名为前缀。
 * Lin-arm / 多数订阅都是这 11 个，默认只开开屏广告。
 */
object GkdCats {
    data class Cat(val key: Int, val name: String, val defaultOn: Boolean)

    val all = listOf(
        Cat(0, "开屏广告", true),
        Cat(1, "青少年模式", false),
        Cat(2, "更新提示", false),
        Cat(3, "评价提示", false),
        Cat(4, "通知提示", false),
        Cat(5, "权限提示", false),
        Cat(6, "局部广告", false),
        Cat(7, "全屏广告", false),
        Cat(8, "分段广告", false),
        Cat(9, "功能类", false),
        Cat(10, "其他", false)
    )

    private val byLongName = all.sortedByDescending { it.name.length }
    private val other = all.last()

    fun of(group: String): Cat {
        val g = group.trim()
        if (g.isEmpty()) return other
        return byLongName.firstOrNull { g.startsWith(it.name) } ?: other
    }
}
