package app.wuad.vpn

import app.wuad.dns.DnsCodec
import app.wuad.dns.WuadDnsServer
import java.util.concurrent.ConcurrentHashMap

/** AdGuard 风格：隧道内对 :53 做最小 TCP，长度前缀 DNS。 */
class DnsTcp(
    private val emit: (ByteArray) -> Unit
) {
    private data class Sess(
        val v6: Boolean,
        val ipHdr: Int,
        val src: ByteArray,
        val dst: ByteArray,
        val sport: Int,
        val dport: Int,
        var recvNext: Long,
        var sendNext: Long,
        val buf: ArrayList<Byte> = ArrayList()
    )

    private val tab = ConcurrentHashMap<String, Sess>()

    fun onSegment(raw: ByteArray, v6: Boolean, ipHdr: Int) {
        if (raw.size < ipHdr + 20) return
        val tcpOff = ipHdr
        val sport = DnsCodec.u16(raw, tcpOff)
        val dport = DnsCodec.u16(raw, tcpOff + 2)
        if (dport != 53) return
        val seq = DnsCodec.u32(raw, tcpOff + 4)
        val dataOff = ((raw[tcpOff + 12].toInt() ushr 4) and 0xf) * 4
        if (dataOff < 20 || raw.size < tcpOff + dataOff) return
        val flags = raw[tcpOff + 13].toInt() and 0xff
        val src = if (v6) raw.copyOfRange(8, 24) else raw.copyOfRange(12, 16)
        val dst = if (v6) raw.copyOfRange(24, 40) else raw.copyOfRange(16, 20)
        val key = key(src, sport, dst, dport)
        val payload = if (raw.size > tcpOff + dataOff) raw.copyOfRange(tcpOff + dataOff, raw.size) else ByteArray(0)

        if (flags and 0x04 != 0) {
            tab.remove(key)
            return
        }
        if (flags and 0x02 != 0) {
            val sess = Sess(v6, ipHdr, src, dst, sport, dport, (seq + 1) and 0xffffffffL, 1L)
            if (tab.size > 32) tab.keys.take(8).forEach { tab.remove(it) }
            tab[key] = sess
            emit(build(raw, sess, 0x12, ByteArray(0), ackOnly = false))
            return
        }
        val sess = tab[key] ?: return
        if (flags and 0x01 != 0) {
            sess.recvNext = (sess.recvNext + payload.size + 1) and 0xffffffffL
            emit(build(raw, sess, 0x11, ByteArray(0), ackOnly = false))
            tab.remove(key)
            return
        }
        if (payload.isEmpty()) return
        sess.recvNext = (sess.recvNext + payload.size) and 0xffffffffL
        payload.forEach { sess.buf.add(it) }
        while (sess.buf.size >= 2) {
            val need = ((sess.buf[0].toInt() and 0xff) shl 8) or (sess.buf[1].toInt() and 0xff)
            if (need <= 0 || need > 4096) {
                tab.remove(key)
                return
            }
            if (sess.buf.size < 2 + need) break
            val dns = ByteArray(need) { i -> sess.buf[2 + i] }
            repeat(2 + need) { sess.buf.removeAt(0) }
            val ans = WuadDnsServer.answer(
                dns,
                ipText(sess.src, sess.v6),
                sess.sport,
                ipText(sess.dst, sess.v6),
                sess.dport
            )
            val framed = ByteArray(2 + ans.size)
            DnsCodec.putU16(framed, 0, ans.size)
            System.arraycopy(ans, 0, framed, 2, ans.size)
            emit(build(raw, sess, 0x18, framed, ackOnly = false))
            sess.sendNext = (sess.sendNext + framed.size) and 0xffffffffL
            emit(build(raw, sess, 0x11, ByteArray(0), ackOnly = false))
            tab.remove(key)
            return
        }
        emit(build(raw, sess, 0x10, ByteArray(0), ackOnly = true))
    }

    private fun ipText(raw: ByteArray, v6: Boolean): String = try {
        java.net.InetAddress.getByAddress(raw).hostAddress.orEmpty()
    } catch (_: Exception) {
        if (v6) "" else raw.joinToString(".") { (it.toInt() and 0xff).toString() }
    }

    private fun key(src: ByteArray, sport: Int, dst: ByteArray, dport: Int) =
        src.joinToString(".") { (it.toInt() and 0xff).toString() } + ":$sport-" +
            dst.joinToString(".") { (it.toInt() and 0xff).toString() } + ":$dport"

    private fun build(orig: ByteArray, sess: Sess, flags: Int, data: ByteArray, ackOnly: Boolean): ByteArray {
        val ipHdr = sess.ipHdr
        val tcpLen = 20 + data.size
        val out = ByteArray(ipHdr + tcpLen)
        System.arraycopy(orig, 0, out, 0, ipHdr)
        if (sess.v6) {
            System.arraycopy(sess.dst, 0, out, 8, 16)
            System.arraycopy(sess.src, 0, out, 24, 16)
            DnsCodec.putU16(out, 4, tcpLen)
            out[6] = 6
            out[7] = 64
        } else {
            System.arraycopy(sess.dst, 0, out, 12, 4)
            System.arraycopy(sess.src, 0, out, 16, 4)
            DnsCodec.putU16(out, 2, ipHdr + tcpLen)
            out[8] = 64
            DnsCodec.putU16(out, 10, 0)
            val ipCks = DnsCodec.ipv4Checksum(out.copyOfRange(0, ipHdr))
            DnsCodec.putU16(out, 10, ipCks.toInt() and 0xffff)
        }
        DnsCodec.putU16(out, ipHdr, sess.dport)
        DnsCodec.putU16(out, ipHdr + 2, sess.sport)
        DnsCodec.putU32(out, ipHdr + 4, sess.sendNext)
        DnsCodec.putU32(out, ipHdr + 8, sess.recvNext)
        out[ipHdr + 12] = 0x50
        out[ipHdr + 13] = flags.toByte()
        DnsCodec.putU16(out, ipHdr + 14, 65535)
        DnsCodec.putU16(out, ipHdr + 16, 0)
        DnsCodec.putU16(out, ipHdr + 18, 0)
        if (data.isNotEmpty()) System.arraycopy(data, 0, out, ipHdr + 20, data.size)
        val tcp = out.copyOfRange(ipHdr, out.size)
        val csum = DnsCodec.tcpChecksum(sess.dst, sess.src, sess.v6, tcp)
        DnsCodec.putU16(out, ipHdr + 16, csum.toInt() and 0xffff)
        if (ackOnly && data.isEmpty() && flags == 0x10) {
            // keep
        }
        return out
    }
}
