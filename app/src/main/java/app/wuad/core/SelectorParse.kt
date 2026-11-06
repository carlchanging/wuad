package app.wuad.core

enum class SelComb { DESC, CHILD, SIBLING, PREV, ANCESTOR, OFFSPRING }

enum class RelOp { PLUS, MINUS, PARENT, CHILD, OFFSPRING }

data class Nth(val any: Boolean, val xs: Set<Int>) {
    fun hit(n: Int): Boolean = any || n in xs

    companion object {
        val ONE = Nth(false, setOf(1))
        val ANY = Nth(true, emptySet())
    }
}

data class Rel(val op: RelOp, val nth: Nth)

data class SelSeg(
    val click: Boolean,
    val tag: String,
    val any: Boolean,
    val attrs: List<Pair<String, String>>,
    val clauses: List<List<List<Pair<String, String>>>> = listOf(listOf(attrs)),
    val rawBrackets: List<String> = emptyList()
)

data class SelStep(val comb: SelComb, val seg: SelSeg)

data class SelPath(val segs: List<SelSeg>, val rels: List<Rel>)

object SelectorParse {
    fun isSupported(sel: String): Boolean {
        val s = unwrap(sel).trim()
        if (s.isEmpty()) return false
        if ("->" in s || "getPrev" in s || "getChild" in s) return false
        return try {
            path(s).segs.isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    fun unwrap(sel: String): String {
        var s = sel.trim()
        while (s.length >= 2 && s.startsWith("(") && s.endsWith(")") && balanced(s, 1, s.length - 2)) {
            s = s.substring(1, s.length - 1).trim()
        }
        return s
    }

    fun parse(sel: String): List<SelStep> {
        val p = path(sel)
        if (p.segs.isEmpty()) return emptyList()
        val out = ArrayList<SelStep>()
        out += SelStep(SelComb.DESC, p.segs[0])
        p.rels.forEachIndexed { i, rel ->
            val comb = when (rel.op) {
                RelOp.PLUS -> SelComb.SIBLING
                RelOp.MINUS -> SelComb.PREV
                RelOp.PARENT -> SelComb.CHILD
                RelOp.CHILD -> SelComb.ANCESTOR
                RelOp.OFFSPRING -> SelComb.OFFSPRING
            }
            out += SelStep(comb, p.segs[i + 1])
        }
        return out
    }

    fun path(sel: String): SelPath {
        val raw = unwrap(sel).trim()
        if (raw.isEmpty()) return SelPath(emptyList(), emptyList())
        cursor = 0
        val segs = ArrayList<SelSeg>()
        val rels = ArrayList<Rel>()
        fun skipWs() { while (cursor < raw.length && raw[cursor].isWhitespace()) cursor++ }
        skipWs()
        segs += segment(readSeg(raw))
        while (cursor < raw.length) {
            skipWs()
            if (cursor >= raw.length) break
            val rel = readRel(raw) ?: break
            skipWs()
            segs += segment(readSeg(raw))
            rels += rel
        }
        return SelPath(segs, rels)
    }

    fun splitOr(sel: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var paren = 0
        val buf = StringBuilder()
        var i = 0
        while (i < sel.length) {
            val c = sel[i]
            when {
                c == '[' -> { depth++; buf.append(c); i++ }
                c == ']' -> { if (depth > 0) depth--; buf.append(c); i++ }
                c == '(' && depth == 0 -> { paren++; buf.append(c); i++ }
                c == ')' && depth == 0 -> { if (paren > 0) paren--; buf.append(c); i++ }
                depth == 0 && paren == 0 && sel.startsWith("||", i) -> {
                    val s = buf.toString().trim()
                    if (s.isNotEmpty()) out.add(s)
                    buf.setLength(0)
                    i += 2
                }
                else -> { buf.append(c); i++ }
            }
        }
        val s = buf.toString().trim()
        if (s.isNotEmpty()) out.add(s)
        return out
    }

    fun segment(raw: String): SelSeg {
        var s = raw.trim()
        val click = s.startsWith("@")
        if (click) s = s.removePrefix("@")
        val any = s == "*" || s.startsWith("*")
        val tag = if (any) "" else Regex("^[A-Za-z][A-Za-z0-9_.]*").find(s)?.value.orEmpty()
        val brackets = ArrayList<String>()
        val clauses = ArrayList<List<List<Pair<String, String>>>>()
        val flat = ArrayList<Pair<String, String>>()
        var i = 0
        while (i < s.length) {
            if (s[i] != '[') { i++; continue }
            val end = closeOf(s, i, '[', ']')
            if (end < 0) break
            val body = s.substring(i + 1, end).trim()
            brackets += body
            val simple = !body.contains('(') || body.startsWith("!(")
            if (simple) {
                val orParts = splitTop(body, "||")
                val clause = orParts.map { part ->
                    splitTop(part, "&&").mapNotNull { parseAttr(it.trim()) }
                }.filter { it.isNotEmpty() }
                if (clause.isNotEmpty()) {
                    clauses.add(clause)
                    clause.forEach { flat.addAll(it) }
                }
            }
            i = end + 1
        }
        return SelSeg(click, tag, any, flat, if (clauses.isEmpty()) listOf(listOf(emptyList())) else clauses, brackets)
    }

    fun parseAttr(body: String): Pair<String, String>? {
        if (body.isEmpty()) return null
        val notAttr = Regex("""^!\(\s*([A-Za-z][\w.]*)\s*=\s*['"]?([^'"]*)['"]?\s*\)$""").find(body.trim())
        if (notAttr != null) return (notAttr.groupValues[1] + "!") to notAttr.groupValues[2]
        val ops = listOf("<=", ">=", "!*=", "!=", "*=", "^=", "$=", "~=", "=", "<", ">")
        for (op in ops) {
            val i = body.indexOf(op)
            if (i <= 0) continue
            val left = body.substring(0, i).trim()
            val value = body.substring(i + op.length).trim().trim('\'', '"')
            if (left.isEmpty()) continue
            val key = when (op) {
                "*=" -> left + "*"
                "^=" -> left + "^"
                "$=" -> left + "$"
                "~=" -> left + "~"
                "!=" -> left + "!"
                "!*=" -> left + "!*"
                else -> left
            }
            val stored = when (op) {
                "<", ">", "<=", ">=" -> op + value
                else -> value
            }
            return key to stored
        }
        return null
    }

    fun quoted(sel: String): List<String> =
        Regex("[\"']([^\"']{1,16})[\"']").findAll(sel).map { it.groupValues[1] }.toList()

    private fun readSeg(raw: String): String {
        val start = iOf(raw)
        var depth = 0
        while (iOf(raw) < raw.length) {
            val c = raw[iOf(raw)]
            if (depth == 0 && (c == '+' || c == '-' || c == '>' || c == '<' || c.isWhitespace())) break
            if (c == '[') depth++
            if (c == ']' && depth > 0) depth--
            bump()
        }
        return raw.substring(start, iOf(raw))
    }

    private fun readRel(raw: String): Rel? {
        if (iOf(raw) >= raw.length) return null
        val c = raw[iOf(raw)]
        val op = when {
            raw.startsWith("<<", iOf(raw)) -> { bump(); bump(); RelOp.OFFSPRING }
            c == '+' -> { bump(); RelOp.PLUS }
            c == '-' -> { bump(); RelOp.MINUS }
            c == '>' -> { bump(); RelOp.PARENT }
            c == '<' -> { bump(); RelOp.CHILD }
            else -> return null
        }
        return Rel(op, readNth(raw, op))
    }

    private fun readNth(raw: String, op: RelOp): Nth {
        var i = iOf(raw)
        while (i < raw.length && raw[i].isWhitespace()) i++
        if (i >= raw.length) {
            setI(i)
            return if (op == RelOp.CHILD || op == RelOp.OFFSPRING) Nth.ANY else Nth.ONE
        }
        if (raw[i] == 'n' && (i + 1 >= raw.length || !raw[i + 1].isLetterOrDigit())) {
            setI(i + 1)
            return Nth.ANY
        }
        if (raw[i].isDigit()) {
            val start = i
            while (i < raw.length && raw[i].isDigit()) i++
            if (i < raw.length && raw[i] == 'n') {
                setI(i + 1)
                return Nth.ANY
            }
            setI(i)
            return Nth(false, setOf(raw.substring(start, i).toInt()))
        }
        if (raw[i] == '(') {
            val end = closeOf(raw, i, '(', ')')
            if (end < 0) {
                setI(i)
                return Nth.ONE
            }
            val body = raw.substring(i + 1, end).trim()
            setI(end + 1)
            return parseNthBody(body, op)
        }
        setI(i)
        return if (op == RelOp.CHILD || op == RelOp.OFFSPRING) Nth.ANY else Nth.ONE
    }

    private fun parseNthBody(body: String, op: RelOp): Nth {
        val b = body.trim()
        if (b == "n" || b == "+n") return Nth.ANY
        if (',' in b) {
            val xs = b.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it > 0 }.toSet()
            return if (xs.isEmpty()) Nth.ANY else Nth(false, xs)
        }
        val poly = Regex("""^([+-]?\d*)n([+-]\d+)?$""").find(b.replace(" ", ""))
        if (poly != null) {
            val a = when (val rawA = poly.groupValues[1]) {
                "", "+" -> 1
                "-" -> -1
                else -> rawA.toInt()
            }
            val c = poly.groupValues[2].ifBlank { "+0" }.toInt()
            if (a == 0) return Nth(false, setOf(c).filter { it > 0 }.toSet().ifEmpty { setOf(1) })
            if (a > 0) return Nth.ANY
            val xs = HashSet<Int>()
            var n = 1
            while (n < 48) {
                val v = a * n + c
                if (v >= 1) xs += v
                n++
            }
            return if (xs.isEmpty()) Nth.ANY else Nth(false, xs)
        }
        val num = b.toIntOrNull()
        if (num != null && num > 0) return Nth(false, setOf(num))
        return if (op == RelOp.CHILD || op == RelOp.OFFSPRING) Nth.ANY else Nth.ONE
    }

    private var cursor = 0
    private fun iOf(raw: String): Int {
        if (cursor > raw.length) cursor = raw.length
        return cursor
    }
    private fun setI(v: Int) { cursor = v }
    private fun bump() { cursor++ }

    private fun closeOf(s: String, from: Int, open: Char, close: Char): Int {
        var d = 0
        var i = from
        while (i < s.length) {
            if (s[i] == open) d++
            else if (s[i] == close) {
                d--
                if (d == 0) return i
            }
            i++
        }
        return -1
    }

    private fun balanced(s: String, from: Int, to: Int): Boolean {
        var d = 0
        var i = from
        while (i <= to) {
            if (s[i] == '(') d++
            else if (s[i] == ')') {
                d--
                if (d < 0) return false
            }
            i++
        }
        return d == 0
    }

    private fun splitTop(src: String, sep: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        val buf = StringBuilder()
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == '(' -> { depth++; buf.append(c); i++ }
                c == ')' -> { if (depth > 0) depth--; buf.append(c); i++ }
                depth == 0 && src.startsWith(sep, i) -> {
                    out.add(buf.toString())
                    buf.setLength(0)
                    i += sep.length
                }
                else -> { buf.append(c); i++ }
            }
        }
        out.add(buf.toString())
        return out
    }
}
