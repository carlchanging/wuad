package app.wuad.core

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

object GkdSelector {
    fun looksLike(sel: String): Boolean = GkdParser.looksSelector(sel)

    fun find(root: AccessibilityNodeInfo, sel: String): AccessibilityNodeInfo? {
        SelectorParse.splitOr(sel).filter { it.isNotEmpty() }.forEach { one ->
            if (!SelectorParse.isSupported(one)) return@forEach
            findOne(root, one)?.let { return it }
        }
        if (!looksLike(sel)) findByWords(root, sel)?.let { return it }
        return null
    }

    private fun findOne(root: AccessibilityNodeInfo, sel: String): AccessibilityNodeInfo? {
        val path = SelectorParse.path(sel)
        if (path.segs.isEmpty()) return null
        val rights = ArrayList<Hit>()
        dfs(root) { n ->
            if (ok(n, path.segs.last())) {
                val click = if (path.segs.last().click) n else null
                rights += Hit(n, click)
            }
        }
        var cur = rights
        for (i in path.rels.indices.reversed()) {
            val rel = path.rels[i]
            val leftSeg = path.segs[i]
            val next = ArrayList<Hit>()
            for (hit in cur) {
                for (left in lookLeft(hit.node, rel, leftSeg)) {
                    next += Hit(left, if (leftSeg.click) left else hit.click)
                }
            }
            cur = next
            if (cur.isEmpty()) return null
        }
        val best = cur.firstOrNull() ?: return null
        return bestClick(best.click ?: best.node)
    }

    private data class Hit(val node: AccessibilityNodeInfo, val click: AccessibilityNodeInfo?)

    private fun lookLeft(right: AccessibilityNodeInfo, rel: Rel, leftSeg: SelSeg): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        when (rel.op) {
            RelOp.PLUS -> {
                val p = right.parent ?: return out
                val at = indexOf(p, right)
                if (at <= 0) return out
                if (rel.nth.any) {
                    for (i in at - 1 downTo 0) takeChild(p, i, leftSeg, out)
                } else rel.nth.xs.forEach { d ->
                    val i = at - d
                    if (i >= 0) takeChild(p, i, leftSeg, out)
                }
            }
            RelOp.MINUS -> {
                val p = right.parent ?: return out
                val at = indexOf(p, right)
                if (at < 0) return out
                if (rel.nth.any) {
                    for (i in at + 1 until p.childCount) takeChild(p, i, leftSeg, out)
                } else rel.nth.xs.forEach { d ->
                    val i = at + d
                    if (i < p.childCount) takeChild(p, i, leftSeg, out)
                }
            }
            RelOp.PARENT -> {
                var p = right.parent
                var d = 1
                while (p != null) {
                    if (rel.nth.hit(d) && ok(p, leftSeg)) out += p
                    if (!rel.nth.any && d >= (rel.nth.xs.maxOrNull() ?: 1)) break
                    p = p.parent
                    d++
                }
            }
            RelOp.CHILD -> {
                if (rel.nth.any) {
                    for (i in 0 until right.childCount) takeChild(right, i, leftSeg, out)
                } else rel.nth.xs.forEach { n ->
                    val i = n - 1
                    if (i in 0 until right.childCount) takeChild(right, i, leftSeg, out)
                }
            }
            RelOp.OFFSPRING -> {
                val bag = ArrayList<AccessibilityNodeInfo>()
                walkDesc(right, leftSeg, bag, true)
                if (rel.nth.any) out += bag
                else rel.nth.xs.forEach { n ->
                    val i = n - 1
                    if (i in bag.indices) out += bag[i]
                }
            }
        }
        return out
    }

    private fun takeChild(p: AccessibilityNodeInfo, i: Int, seg: SelSeg, out: ArrayList<AccessibilityNodeInfo>) {
        val c = p.getChild(i) ?: return
        if (ok(c, seg)) out += c
    }

    private fun walkDesc(
        n: AccessibilityNodeInfo,
        seg: SelSeg,
        out: ArrayList<AccessibilityNodeInfo>,
        skipSelf: Boolean
    ) {
        if (!skipSelf && ok(n, seg)) out += n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            walkDesc(c, seg, out, false)
        }
    }

    private fun dfs(n: AccessibilityNodeInfo, take: (AccessibilityNodeInfo) -> Unit) {
        take(n)
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            dfs(c, take)
        }
    }

    fun ok(n: AccessibilityNodeInfo, seg: SelSeg): Boolean {
        if (seg.tag.isNotEmpty() && !tagHit(n.className?.toString().orEmpty(), seg.tag)) return false
        if (seg.rawBrackets.isNotEmpty()) {
            if (!seg.rawBrackets.all { evalBracket(n, it) }) return false
        } else {
            val clauses = seg.clauses
            val pass = if (clauses.isEmpty() || (clauses.size == 1 && clauses[0].size == 1 && clauses[0][0].isEmpty())) {
                seg.attrs.all { attrOk(n, it.first, it.second) }
            } else {
                clauses.all { orParts -> orParts.any { ands -> ands.all { attrOk(n, it.first, it.second) } } }
            }
            if (!pass) return false
        }
        return seg.any || seg.tag.isNotEmpty() || seg.attrs.isNotEmpty() ||
            seg.rawBrackets.isNotEmpty() ||
            seg.clauses.any { it.any { g -> g.isNotEmpty() } }
    }

    private fun evalBracket(n: AccessibilityNodeInfo, body: String): Boolean {
        val t = body.trim()
        if (t.isEmpty()) return true
        return evalBool(n, t)
    }

    private fun evalBool(n: AccessibilityNodeInfo, expr: String): Boolean {
        val e = stripParen(expr)
        val orParts = splitTop(e, "||")
        return orParts.any { andPart ->
            splitTop(andPart, "&&").all { atom(n, it.trim()) }
        }
    }

    private fun atom(n: AccessibilityNodeInfo, raw: String): Boolean {
        var s = raw.trim()
        if (s.isEmpty()) return true
        if (s.startsWith("!(") && s.endsWith(")") && !s.drop(2).dropLast(1).contains('(')) {
            val inner = SelectorParse.parseAttr(s) ?: return false
            return attrOk(n, inner.first, inner.second)
        }
        if (s.startsWith("(") && s.endsWith(")")) return evalBool(n, s)
        val attr = SelectorParse.parseAttr(s) ?: return false
        return attrOk(n, attr.first, attr.second)
    }

    private fun stripParen(expr: String): String {
        var s = expr.trim()
        while (s.startsWith("(") && s.endsWith(")") && balanced(s)) {
            s = s.substring(1, s.length - 1).trim()
        }
        return s
    }

    private fun balanced(s: String): Boolean {
        var d = 0
        for (i in s.indices) {
            if (s[i] == '(') d++
            else if (s[i] == ')') {
                d--
                if (d == 0 && i != s.lastIndex) return false
                if (d < 0) return false
            }
        }
        return d == 0
    }

    private fun splitTop(src: String, sep: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        val buf = StringBuilder()
        var i = 0
        while (i < src.length) {
            when {
                src[i] == '(' -> { depth++; buf.append(src[i]); i++ }
                src[i] == ')' -> { if (depth > 0) depth--; buf.append(src[i]); i++ }
                depth == 0 && src.startsWith(sep, i) -> {
                    out.add(buf.toString())
                    buf.setLength(0)
                    i += sep.length
                }
                else -> { buf.append(src[i]); i++ }
            }
        }
        out.add(buf.toString())
        return out
    }

    private fun attrOk(n: AccessibilityNodeInfo, k: String, v: String): Boolean {
        val text = n.text?.toString().orEmpty()
        val desc = n.contentDescription?.toString().orEmpty()
        val vid = n.viewIdResourceName.orEmpty()
        val cls = n.className?.toString().orEmpty()
        val box = Rect()
        n.getBoundsInScreen(box)
        if (k == "index") return indexOk(n, v)
        return when (k) {
            "text" -> text == v
            "text*" -> text.contains(v)
            "text^" -> text.startsWith(v)
            "text$" -> text.endsWith(v)
            "text!" -> text != v
            "text!*" -> !text.contains(v)
            "text~" -> regexOk(text, v)
            "text.length" -> numOk(text.length, v)
            "desc" -> desc == v
            "desc*" -> desc.contains(v)
            "desc^" -> desc.startsWith(v)
            "desc$" -> desc.endsWith(v)
            "desc.length" -> numOk(desc.length, v)
            "name" -> cls == v || cls.endsWith(".$v")
            "name*" -> cls.contains(v)
            "name^" -> cls.startsWith(v)
            "name$" -> cls.endsWith(v)
            "id" -> vid == v
            "id*" -> vid.contains(v)
            "id$" -> vid.endsWith(v)
            "vid" -> vid.endsWith("/$v") || vid == v
            "vid!" -> !vid.endsWith("/$v") && vid != v
            "vid*" -> vid.contains(v)
            "clickable" -> n.isClickable == (v == "true")
            "visibleToUser", "visible" -> n.isVisibleToUser == (v == "true")
            "childCount" -> numOk(n.childCount, v)
            "width" -> numOk(box.width(), v)
            "height" -> numOk(box.height(), v)
            else -> false
        }
    }

    private fun indexOk(n: AccessibilityNodeInfo, expr: String): Boolean {
        val idx = indexOf(n.parent, n)
        val e = expr.trim()
        val minus = Regex("""parent\.childCount\.minus\((\d+)\)""").find(e)
        if (minus != null) {
            val count = n.parent?.childCount ?: 0
            return idx == count - (minus.groupValues[1].toIntOrNull() ?: 0)
        }
        if (e == "parent.childCount" || e.startsWith("parent.childCount")) {
            return idx == (n.parent?.childCount ?: -1)
        }
        return numOk(idx, if (e.firstOrNull()?.isDigit() == true) e else e)
    }

    private fun regexOk(text: String, expr: String): Boolean {
        return try {
            val body = expr.removePrefix("(?is)").removePrefix("(?i)")
            Regex(body, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).containsMatchIn(text)
        } catch (_: Exception) {
            false
        }
    }

    private fun tagHit(cls: String, tag: String): Boolean {
        if (cls.endsWith(tag) || cls.contains(".$tag") || cls.contains(tag)) return true
        if (tag.equals("ViewGroup", true)) {
            return cls.contains("ViewGroup") || cls.contains("Layout") ||
                cls.contains("ViewPager") || cls.contains("ScrollView")
        }
        return false
    }

    private fun numOk(n: Int, expr: String): Boolean {
        val e = expr.trim()
        return when {
            e.startsWith("<=") -> n <= (e.drop(2).toIntOrNull() ?: return true)
            e.startsWith(">=") -> n >= (e.drop(2).toIntOrNull() ?: return true)
            e.startsWith("!=") -> n != e.drop(2).toIntOrNull()
            e.startsWith("<") -> n < (e.drop(1).toIntOrNull() ?: return true)
            e.startsWith(">") -> n > (e.drop(1).toIntOrNull() ?: return true)
            e.startsWith("=") -> n == e.drop(1).toIntOrNull()
            else -> n == e.toIntOrNull()
        }
    }

    fun bestClick(n: AccessibilityNodeInfo): AccessibilityNodeInfo {
        if (n.isClickable) return n
        n.parent?.takeIf { it.isClickable && it.isVisibleToUser }?.let { return it }
        return n
    }

    fun skipLike(n: AccessibilityNodeInfo): Boolean {
        val t = ((n.text ?: n.contentDescription) ?: "").toString()
        return SplashHunt.textHit(t) || SplashHunt.vidHit(n.viewIdResourceName.orEmpty())
    }

    private fun indexOf(parent: AccessibilityNodeInfo?, node: AccessibilityNodeInfo): Int {
        if (parent == null) return -1
        for (i in 0 until parent.childCount) {
            val c = parent.getChild(i) ?: continue
            if (same(c, node)) return i
        }
        return -1
    }

    private fun same(a: AccessibilityNodeInfo, b: AccessibilityNodeInfo): Boolean {
        val aid = a.viewIdResourceName.orEmpty()
        val bid = b.viewIdResourceName.orEmpty()
        if (aid.isNotEmpty() && aid == bid) return true
        return a.className == b.className &&
            a.text == b.text &&
            a.contentDescription == b.contentDescription &&
            a.childCount == b.childCount
    }

    private fun findByWords(root: AccessibilityNodeInfo, sel: String): AccessibilityNodeInfo? {
        val words = sel.split("||", "|", "/", "、", ",", " ")
            .map { it.trim() }
            .filter { it.length in 1..12 && !it.startsWith("[") && !it.startsWith("@") && it != "*" }
        if (words.isEmpty()) return null
        return walkWords(root, words)
    }

    private fun walkWords(n: AccessibilityNodeInfo, words: List<String>): AccessibilityNodeInfo? {
        val t = ((n.text ?: n.contentDescription) ?: "").toString()
        if (t.length in 1..16 && words.any { t.contains(it) }) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            walkWords(c, words)?.let { return it }
        }
        return null
    }
}
