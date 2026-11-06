package app.wuad.core

import org.json.JSONArray
import org.json.JSONObject

object GkdParser {
    const val FORMAT = 4
    const val OFFICIAL_SPLASH =
        "[text*=\"跳过\"][text.length<10][width<500 && height<300][visibleToUser=true]"

    private data class Picked(
        val match: String,
        val andMatch: String,
        val activity: String,
        val excludeActivity: String,
        val excludeMatch: String,
        val action: String,
        val delay: Int,
        val matchTime: Int,
        val actionMaximum: Int,
        val resetMatch: String
    )

    fun toCompact(raw: String): String {
        val root = parseRoot(raw)
        val rows = JSONArray()
        val apps = root.optJSONArray("apps") ?: JSONArray()
        for (i in 0 until apps.length()) {
            val app = apps.getJSONObject(i)
            val pkg = app.optString("id")
            val name = app.optString("name", pkg)
            val groups = app.optJSONArray("groups") ?: JSONArray()
            for (j in 0 until groups.length()) {
                expand(pkg, name, groups.getJSONObject(j)).forEach { rows.put(it) }
            }
        }
        val globals = root.optJSONArray("globalGroups") ?: JSONArray()
        for (i in 0 until globals.length()) {
            expand("*", "全局", globals.getJSONObject(i)).forEach { rows.put(it) }
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("version", root.opt("version"))
            .put("rules", rows)
            .toString()
    }

    fun splashBundle(seed: String): String {
        val s = seed.trim()
        return if (s.isEmpty() || s == "跳过" || !looksSelector(s)) OFFICIAL_SPLASH else s
    }

    fun looksSelector(sel: String): Boolean =
        sel.contains('[') || sel.contains('@') || sel.contains("vid=") ||
            sel.contains('+') || sel.contains('>') || sel.contains("||")

    private fun parseRoot(raw: String): JSONObject {
        val trimmed = raw.trim()
        return try {
            JSONObject(trimmed)
        } catch (_: Exception) {
            JSONObject(Json5Lite.toJson(trimmed))
        }
    }

    private fun expand(pkg: String, app: String, g: JSONObject): List<JSONObject> {
        val raw = g.opt("rules")
        if (raw is JSONArray && (0 until raw.length()).any { raw.opt(it) is JSONObject }) {
            val out = ArrayList<JSONObject>()
            for (i in 0 until raw.length()) {
                when (val it = raw.opt(i)) {
                    is String -> if (it.isNotBlank()) {
                        val one = JSONObject(g.toString()).put("rules", it)
                        out += row("$pkg:${g.opt("key")}:$i", pkg, app, one, i, "")
                    }
                    is JSONObject -> {
                        val key = it.optInt("key", i)
                        val one = JSONObject(g.toString()).put("rules", JSONArray().put(it))
                        out += row("$pkg:${g.opt("key")}:$key", pkg, app, one, key, preKeysOf(it))
                    }
                }
            }
            if (out.isNotEmpty()) return out
        }
        return listOf(row("$pkg:${g.opt("key")}", pkg, app, g, 0, ""))
    }

    private fun preKeysOf(o: JSONObject): String = when (val p = o.opt("preKeys")) {
        is Number -> p.toInt().toString()
        is String -> p
        is JSONArray -> (0 until p.length()).map { p.opt(it).toString() }.filter { it.isNotBlank() }.joinToString(",")
        else -> ""
    }

    private fun row(id: String, pkg: String, app: String, g: JSONObject, ruleKey: Int = 0, preKeys: String = ""): JSONObject {
        val gname = g.optString("name", "规则")
        val p = pick(g)
        return JSONObject()
            .put("id", id)
            .put("pkg", pkg)
            .put("app", app)
            .put("group", gname)
            .put("match", p.match)
            .put("andMatch", p.andMatch)
            .put("activity", p.activity)
            .put("excludeActivity", p.excludeActivity.ifBlank { joinAct(g.opt("excludeActivityIds")) })
            .put("excludeMatch", p.excludeMatch)
            .put("action", p.action)
            .put("delay", p.delay)
            .put("matchTime", if (p.matchTime > 0) p.matchTime else g.optInt("matchTime", 0))
            .put("actionMaximum", if (p.actionMaximum > 0) p.actionMaximum else g.optInt("actionMaximum", 0))
            .put("resetMatch", p.resetMatch.ifBlank { g.optString("resetMatch") })
            .put("disableIfGroup", g.optString("disableIfAppGroupMatch"))
            .put("matchAnyApp", if (pkg == "*") g.optBoolean("matchAnyApp", true) else true)
            .put("matchLauncher", if (pkg == "*") g.optBoolean("matchLauncher", false) else false)
            .put("matchSystemApp", if (pkg == "*") g.optBoolean("matchSystemApp", false) else false)
            .put("denyApps", if (pkg == "*") appIds(g.opt("apps"), false) else "")
            .put("allowApps", if (pkg == "*") appIds(g.opt("apps"), true) else "")
            .put("ruleKey", ruleKey)
            .put("preKeys", preKeys)
            .put("enabled", g.optBoolean("enable", true))
    }

    private fun pick(g: JSONObject): Picked {
        scanRules(g.opt("rules"), g)?.let { return it }
        val any = collectList(g, "anyMatches")
        val ands = collectList(g, "matches")
        return finish(any, ands, g, g, "开屏" in g.optString("name"))
    }

    private fun scanRules(raw: Any?, group: JSONObject): Picked? {
        val any = ArrayList<String>()
        val ands = ArrayList<String>()
        var act = joinAct(group.opt("activityIds"))
        var excludeAct = joinAct(group.opt("excludeActivityIds"))
        var excludeMatch = ""
        var action = "click"
        var delay = delayOf(group)
        var matchTime = group.optInt("matchTime", 0)
        var actionMaximum = group.optInt("actionMaximum", 0)
        var resetMatch = group.optString("resetMatch")
        var hit = false
        when (raw) {
            is String -> if (raw.isNotBlank()) {
                any.add(raw)
                hit = true
            }
            is JSONArray -> {
                for (i in 0 until raw.length()) {
                    when (val it = raw.opt(i)) {
                        is String -> if (it.isNotBlank()) {
                            any.add(it)
                            hit = true
                        }
                        is JSONObject -> {
                            val orParts = collectList(it, "anyMatches")
                            val andParts = collectList(it, "matches")
                            if (orParts.isNotEmpty() || andParts.isNotEmpty()) {
                                any.addAll(orParts)
                                if (orParts.isEmpty() && andParts.size >= 2) ands.addAll(andParts)
                                else any.addAll(andParts)
                                act = joinAct(it.opt("activityIds")).ifBlank { act }
                                excludeAct = joinAct(it.opt("excludeActivityIds")).ifBlank { excludeAct }
                                val ex = joinMatches(it.opt("excludeMatches"))
                                if (ex.isNotBlank()) excludeMatch = listOf(excludeMatch, ex).filter { s -> s.isNotBlank() }.joinToString("||")
                                action = it.optString("action", action).ifBlank { action }
                                val d = delayOf(it, group)
                                if (d > 0) delay = d
                                if (it.optInt("matchTime", 0) > 0) matchTime = it.optInt("matchTime")
                                if (it.optInt("actionMaximum", 0) > 0) actionMaximum = it.optInt("actionMaximum")
                                if (it.optString("resetMatch").isNotBlank()) resetMatch = it.optString("resetMatch")
                                hit = true
                            }
                        }
                    }
                }
            }
        }
        if (!hit) return null
        val splash = "开屏" in group.optString("name")
        return finish(any, ands, group, group, splash, act, excludeAct, excludeMatch, action, delay, matchTime, actionMaximum, resetMatch)
    }

    private fun finish(
        any: List<String>,
        ands: List<String>,
        group: JSONObject,
        fallback: JSONObject,
        splash: Boolean,
        act: String = joinAct(group.opt("activityIds")),
        excludeAct: String = joinAct(group.opt("excludeActivityIds")),
        excludeMatch: String = joinMatches(group.opt("excludeMatches")),
        action: String = group.optString("action", "click"),
        delay: Int = delayOf(group),
        matchTime: Int = group.optInt("matchTime", 0),
        actionMaximum: Int = group.optInt("actionMaximum", 0),
        resetMatch: String = group.optString("resetMatch")
    ): Picked {
        val or = any.distinct().filter { it.isNotBlank() }
        val and = ands.distinct().filter { it.isNotBlank() }
        return Picked(
            or.joinToString("||"),
            and.joinToString("\n"),
            act,
            excludeAct,
            excludeMatch.ifBlank { joinMatches(fallback.opt("excludeMatches")) },
            action.ifBlank { "click" },
            delay,
            matchTime,
            actionMaximum,
            resetMatch
        )
    }

    private fun collectList(obj: JSONObject, key: String): List<String> {
        val out = ArrayList<String>()
        fun eat(raw: Any?) {
            when (raw) {
                is String -> if (raw.isNotBlank()) out.add(raw.trim())
                is JSONArray -> for (i in 0 until raw.length()) eat(raw.opt(i))
            }
        }
        eat(obj.opt(key))
        if (key == "matches") eat(obj.opt("match"))
        return out.distinct()
    }

    private fun joinMatches(raw: Any?): String = when (raw) {
        is String -> raw
        is JSONArray -> (0 until raw.length()).map { raw.optString(it) }.filter { it.isNotBlank() }.joinToString("||")
        else -> ""
    }

    private fun delayOf(vararg objs: JSONObject): Int {
        objs.forEach { o ->
            val n = o.optInt("actionDelay", o.optInt("preDelay", o.optInt("delay", 0)))
            if (n > 0) return n.coerceAtMost(5000)
        }
        return 0
    }

    private fun joinAct(raw: Any?): String = when (raw) {
        is String -> raw
        is JSONArray -> (0 until raw.length()).joinToString("|") { raw.optString(it) }
        else -> ""
    }

    private fun appIds(raw: Any?, enable: Boolean): String {
        val arr = raw as? JSONArray ?: return ""
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            if (o.optBoolean("enable", true) == enable) out.add(id)
        }
        return out.joinToString(",")
    }
}
