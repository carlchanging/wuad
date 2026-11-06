package app.wuad.core

data class GkdAppRow(
    val pkg: String,
    val name: String,
    val items: List<GkdItem>
) {
    val total: Int get() = items.size
    val onCount: Int get() = items.count { it.enabled }
    val splashOn: Boolean get() = items.any { it.enabled && "开屏" in it.group }
}

object GkdGroups {
    @Volatile private var cachedGen = -1
    @Volatile private var cached: List<GkdAppRow>? = null

    fun invalidate() {
        cached = null
        cachedGen = -1
    }

    fun of(items: List<GkdItem>, query: String = ""): List<GkdAppRow> {
        val grouped = grouped(items)
        val q = query.trim().lowercase()
        if (q.isEmpty()) return grouped
        return grouped.mapNotNull { app ->
            if (app.name.lowercase().contains(q) || app.pkg.lowercase().contains(q)) app
            else {
                val rows = app.items.filter { hit(it, q) }
                if (rows.isEmpty()) null else app.copy(items = rows)
            }
        }
    }

    fun ofPkg(items: List<GkdItem>, pkg: String, query: String = ""): List<GkdItem> {
        val app = grouped(items).find { it.pkg == pkg } ?: return emptyList()
        val q = query.trim().lowercase()
        if (q.isEmpty()) return app.items
        return app.items.filter { hit(it, q) }
    }

    private fun grouped(items: List<GkdItem>): List<GkdAppRow> {
        val gen = RuleStore.gkdGen
        val hit = cached
        if (hit != null && cachedGen == gen) return hit
        val built = items
            .groupBy { it.pkg.ifBlank { "*" } }
            .map { (pkg, rows) ->
                GkdAppRow(
                    pkg,
                    rows.first().app.ifBlank { pkg },
                    rows.sortedWith(compareByDescending<GkdItem> { "开屏" in it.group }.thenBy { it.group })
                )
            }
            .sortedWith(
                compareByDescending<GkdAppRow> { it.pkg == "*" }
                    .thenByDescending { it.splashOn }
                    .thenBy { it.name }
            )
        cached = built
        cachedGen = gen
        return built
    }

    private fun hit(it: GkdItem, q: String): Boolean {
        if (q.isEmpty()) return true
        return it.app.lowercase().contains(q) ||
            it.pkg.lowercase().contains(q) ||
            it.group.lowercase().contains(q) ||
            it.match.lowercase().contains(q)
    }
}
