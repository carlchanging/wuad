package app.wuad.core

object TrafficFmt {
    fun human(bytes: Long): String {
        if (bytes < 0) return "—"
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (bytes < 1024L * 1024) return trim(kb) + " KB"
        val mb = kb / 1024.0
        if (bytes < 1024L * 1024 * 1024) return trim(mb) + " MB"
        return trim(mb / 1024.0) + " GB"
    }

    private fun trim(v: Double): String =
        if (v >= 10) v.toInt().toString() else String.format(java.util.Locale.US, "%.1f", v)
}
