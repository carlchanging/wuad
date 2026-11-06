package app.wuad.core

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object RemoteFiles {
    fun downloadFirst(urls: List<String>, dest: File): Pair<String, Long> {
        var last = Exception("没有可用地址")
        urls.distinct().filter { it.isNotBlank() }.forEach { url ->
            try {
                return url to download(url, dest)
            } catch (e: Exception) {
                last = e
            }
        }
        throw last
    }

    fun download(url: String, dest: File, connectMs: Int = 20000, readMs: Int = 60000): Long {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.absolutePath + ".part")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectMs
            readTimeout = readMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Wuad/0.1")
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${conn.responseCode}")
            }
            conn.inputStream.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            if (tmp.length() < 32) throw IllegalStateException("文件太小，不像规则")
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            return dest.length()
        } finally {
            conn.disconnect()
        }
    }

    fun fmtTime(t: Long): String {
        if (t <= 0) return "从未更新"
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
        return sdf.format(java.util.Date(t))
    }
}
