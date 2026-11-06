package app.wuad.dns

import java.nio.ByteBuffer
import java.nio.ByteOrder

object DnsCodec {
    fun readQueryName(payload: ByteArray): String? {
        if (payload.size < 13) return null
        var i = 12
        val labels = ArrayList<String>()
        var jumps = 0
        var cursor = i
        while (cursor < payload.size) {
            val len = payload[cursor].toInt() and 0xff
            if (len == 0) break
            if (len and 0xc0 == 0xc0) {
                if (cursor + 1 >= payload.size) return null
                cursor = ((len and 0x3f) shl 8) or (payload[cursor + 1].toInt() and 0xff)
                if (++jumps > 8) return null
                continue
            }
            if (cursor + 1 + len > payload.size) return null
            labels.add(String(payload, cursor + 1, len, Charsets.US_ASCII))
            cursor += 1 + len
            if (labels.size > 20) return null
        }
        return labels.joinToString(".").lowercase().ifEmpty { null }
    }

    fun nxdomain(query: ByteArray): ByteArray {
        val out = query.copyOf(query.size.coerceAtMost(512))
        if (out.size < 12) return out
        out[2] = (0x80 or (out[2].toInt() and 0x01)).toByte()
        out[3] = (0x83).toByte()
        return out
    }

    fun qtype(query: ByteArray): Int {
        val end = skipName(query, 12) ?: return 0
        if (end + 2 > query.size) return 0
        return u16(query, end)
    }

    /** AdGuard unspecified-address：A→0.0.0.0，AAAA→::，其它 NOERROR 空应答。 */
    fun sinkhole(query: ByteArray): ByteArray {
        if (query.size < 12) return nxdomain(query)
        val qEnd = skipName(query, 12) ?: return nxdomain(query)
        if (qEnd + 4 > query.size) return nxdomain(query)
        val type = u16(query, qEnd)
        val question = query.copyOfRange(0, qEnd + 4)
        val rdata = when (type) {
            1 -> byteArrayOf(0, 0, 0, 0)
            28 -> ByteArray(16)
            else -> {
                val out = question.copyOf()
                out[2] = 0x81.toByte()
                out[3] = 0x80.toByte()
                putU16(out, 6, 0)
                return out
            }
        }
        val out = ByteArray(question.size + 12 + rdata.size)
        System.arraycopy(question, 0, out, 0, question.size)
        out[2] = 0x81.toByte()
        out[3] = 0x80.toByte()
        putU16(out, 6, 1)
        var o = question.size
        out[o] = 0xC0.toByte()
        out[o + 1] = 0x0C.toByte()
        putU16(out, o + 2, type)
        putU16(out, o + 4, 1)
        out[o + 6] = 0
        out[o + 7] = 0
        out[o + 8] = 0
        out[o + 9] = 60
        putU16(out, o + 10, rdata.size)
        System.arraycopy(rdata, 0, out, o + 12, rdata.size)
        return out
    }

    fun servfail(query: ByteArray): ByteArray {
        val out = query.copyOf(query.size.coerceAtMost(512))
        if (out.size < 12) return out
        out[2] = (0x80 or (out[2].toInt() and 0x01)).toByte()
        out[3] = 0x82.toByte()
        return out
    }

    fun aRecords(payload: ByteArray): List<String> {
        if (payload.size < 12) return emptyList()
        var i = 12
        repeat(u16(payload, 4)) {
            i = skipName(payload, i) ?: return emptyList()
            if (i + 4 > payload.size) return emptyList()
            i += 4
        }
        val out = ArrayList<String>()
        repeat(u16(payload, 6)) {
            i = skipName(payload, i) ?: return out
            if (i + 10 > payload.size) return out
            val type = u16(payload, i)
            val rdlen = u16(payload, i + 8)
            i += 10
            if (type == 1 && rdlen == 4 && i + 4 <= payload.size) {
                out.add(
                    (0..3).joinToString(".") { b -> (payload[i + b].toInt() and 0xff).toString() }
                )
            }
            i += rdlen
        }
        return out
    }

    fun cnames(payload: ByteArray): List<String> {
        if (payload.size < 12) return emptyList()
        var i = 12
        repeat(u16(payload, 4)) {
            i = skipName(payload, i) ?: return emptyList()
            if (i + 4 > payload.size) return emptyList()
            i += 4
        }
        val out = ArrayList<String>()
        repeat(u16(payload, 6)) {
            i = skipName(payload, i) ?: return out
            if (i + 10 > payload.size) return out
            val type = u16(payload, i)
            val rdlen = u16(payload, i + 8)
            i += 10
            if (type == 5 && i + rdlen <= payload.size) {
                readNameAt(payload, i)?.let { out.add(it) }
            }
            i += rdlen
        }
        return out
    }

    fun queryA(id: Int, host: String): ByteArray = queryType(id, host, 1)

    fun queryAAAA(id: Int, host: String): ByteArray = queryType(id, host, 28)

    fun queryType(id: Int, host: String, type: Int): ByteArray {
        val labels = host.trim('.').split('.').filter { it.isNotEmpty() }
        val q = ByteArray(12 + labels.sumOf { 1 + it.length } + 1 + 4)
        putU16(q, 0, id)
        q[2] = 1
        putU16(q, 4, 1)
        var o = 12
        labels.forEach { lab ->
            q[o] = lab.length.toByte()
            lab.toByteArray(Charsets.US_ASCII).copyInto(q, o + 1)
            o += 1 + lab.length
        }
        q[o] = 0
        putU16(q, o + 1, type)
        putU16(q, o + 3, 1)
        return q
    }

    fun ipv6TransportChecksum(src: ByteArray, dst: ByteArray, next: Int, payload: ByteArray): Short {
        var sum = 0L
        fun add(b: ByteArray) {
            var i = 0
            while (i + 1 < b.size) {
                sum += ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
                i += 2
            }
            if (i < b.size) sum += (b[i].toInt() and 0xff) shl 8
        }
        add(src)
        add(dst)
        sum += payload.size ushr 16
        sum += payload.size and 0xffff
        sum += next
        add(payload)
        while (sum ushr 16 != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        return (sum.inv() and 0xffff).toShort()
    }

    fun tcpChecksum(src: ByteArray, dst: ByteArray, v6: Boolean, tcp: ByteArray): Short {
        return if (v6) ipv6TransportChecksum(src, dst, 6, tcp)
        else {
            var sum = 0
            fun add(b: ByteArray) {
                var i = 0
                while (i + 1 < b.size) {
                    sum += ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
                    i += 2
                }
                if (i < b.size) sum += (b[i].toInt() and 0xff) shl 8
            }
            add(src)
            add(dst)
            sum += 6
            sum += tcp.size
            add(tcp)
            while (sum ushr 16 != 0) sum = (sum and 0xffff) + (sum ushr 16)
            (sum.inv() and 0xffff).toShort()
        }
    }

    fun u32(buf: ByteArray, off: Int): Long =
        ((buf[off].toLong() and 0xff) shl 24) or
            ((buf[off + 1].toLong() and 0xff) shl 16) or
            ((buf[off + 2].toLong() and 0xff) shl 8) or
            (buf[off + 3].toLong() and 0xff)

    fun putU32(buf: ByteArray, off: Int, v: Long) {
        val n = v and 0xffffffffL
        buf[off] = ((n ushr 24) and 0xff).toByte()
        buf[off + 1] = ((n ushr 16) and 0xff).toByte()
        buf[off + 2] = ((n ushr 8) and 0xff).toByte()
        buf[off + 3] = (n and 0xff).toByte()
    }

    fun skipName(buf: ByteArray, start: Int): Int? {
        var cursor = start
        var jumps = 0
        var end: Int? = null
        while (cursor < buf.size) {
            val len = buf[cursor].toInt() and 0xff
            if (len == 0) return end ?: (cursor + 1)
            if (len and 0xc0 == 0xc0) {
                if (end == null) end = cursor + 2
                if (cursor + 1 >= buf.size) return null
                cursor = ((len and 0x3f) shl 8) or (buf[cursor + 1].toInt() and 0xff)
                if (++jumps > 10) return null
                continue
            }
            cursor += 1 + len
        }
        return null
    }

    fun readNameAt(payload: ByteArray, start: Int): String? {
        var cursor = start
        val labels = ArrayList<String>()
        var jumps = 0
        while (cursor < payload.size) {
            val len = payload[cursor].toInt() and 0xff
            if (len == 0) break
            if (len and 0xc0 == 0xc0) {
                if (cursor + 1 >= payload.size) return null
                cursor = ((len and 0x3f) shl 8) or (payload[cursor + 1].toInt() and 0xff)
                if (++jumps > 8) return null
                continue
            }
            if (cursor + 1 + len > payload.size) return null
            labels.add(String(payload, cursor + 1, len, Charsets.US_ASCII))
            cursor += 1 + len
            if (labels.size > 20) return null
        }
        return labels.joinToString(".").lowercase().ifEmpty { null }
    }

    fun udpChecksum(src: ByteArray, dst: ByteArray, udp: ByteArray): Short {
        var sum = 0
        fun add(b: ByteArray) {
            var i = 0
            while (i + 1 < b.size) {
                sum += ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
                i += 2
            }
            if (i < b.size) sum += (b[i].toInt() and 0xff) shl 8
        }
        add(src)
        add(dst)
        sum += 17
        sum += udp.size
        add(udp)
        while (sum ushr 16 != 0) sum = (sum and 0xffff) + (sum ushr 16)
        val c = sum.inv() and 0xffff
        return c.toShort()
    }

    fun ipv4Checksum(header: ByteArray): Short {
        var sum = 0
        var i = 0
        while (i < header.size) {
            sum += ((header[i].toInt() and 0xff) shl 8) or (header[i + 1].toInt() and 0xff)
            i += 2
        }
        while (sum ushr 16 != 0) sum = (sum and 0xffff) + (sum ushr 16)
        return (sum.inv() and 0xffff).toShort()
    }

    fun putU16(buf: ByteArray, off: Int, v: Int) {
        buf[off] = ((v ushr 8) and 0xff).toByte()
        buf[off + 1] = (v and 0xff).toByte()
    }

    fun u16(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xff) shl 8) or (buf[off + 1].toInt() and 0xff)

    fun wrap(buf: ByteArray): ByteBuffer = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN)
}
