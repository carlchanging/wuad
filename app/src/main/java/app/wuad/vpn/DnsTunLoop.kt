package app.wuad.vpn

import android.os.ParcelFileDescriptor
import app.wuad.dns.DnsCodec
import app.wuad.dns.WuadDnsServer
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 分流关时：劫持 IPv4/IPv6 的 UDP/TCP 53，交给本地 DNS。 */
class DnsTunLoop(
    fd: ParcelFileDescriptor
) : Thread("wuad-tun") {
    private val input = FileInputStream(fd.fileDescriptor)
    private val output = FileOutputStream(fd.fileDescriptor)
    private val dead = AtomicBoolean(false)
    private val outLock = Any()
    private val pool = Executors.newFixedThreadPool(4)
    private val tcp = DnsTcp { pkt -> writeRaw(pkt) }

    override fun run() {
        val packet = ByteArray(32767)
        while (!dead.get() && WuadVpnService.running.get()) {
            val n = try {
                input.read(packet)
            } catch (_: Exception) {
                break
            }
            if (n <= 0) break
            handle(packet.copyOf(n))
        }
    }

    fun cancel() {
        dead.set(true)
        pool.shutdownNow()
        try { input.close() } catch (_: Exception) {}
        try { output.close() } catch (_: Exception) {}
        interrupt()
    }

    private fun handle(buf: ByteArray) {
        if (buf.size < 20) return
        when ((buf[0].toInt() ushr 4) and 0xf) {
            4 -> handle4(buf)
            6 -> handle6(buf)
        }
    }

    private fun handle4(buf: ByteArray) {
        val ihl = (buf[0].toInt() and 0xf) * 4
        if (ihl < 20 || buf.size < ihl + 8) return
        when (buf[9].toInt() and 0xff) {
            17 -> {
                if (DnsCodec.u16(buf, ihl + 2) != 53) return
                val dns = buf.copyOfRange(ihl + 8, buf.size)
                pool.execute {
                    if (dead.get()) return@execute
                    writeUdp4(
                        buf,
                        ihl,
                        WuadDnsServer.answer(
                            dns,
                            ipv4(buf, 12),
                            DnsCodec.u16(buf, ihl),
                            ipv4(buf, 16),
                            53
                        )
                    )
                }
            }
            6 -> tcp.onSegment(buf, false, ihl)
        }
    }

    private fun handle6(buf: ByteArray) {
        if (buf.size < 48) return
        var next = buf[6].toInt() and 0xff
        var off = 40
        var guard = 0
        while (next != 17 && next != 6 && off + 2 <= buf.size && guard++ < 6) {
            val nxt = buf[off].toInt() and 0xff
            val extLen = ((buf[off + 1].toInt() and 0xff) + 1) * 8
            next = nxt
            off += extLen
        }
        if (buf.size < off + 8) return
        when (next) {
            17 -> {
                if (DnsCodec.u16(buf, off + 2) != 53) return
                val dns = buf.copyOfRange(off + 8, buf.size)
                pool.execute {
                    if (dead.get()) return@execute
                    writeUdp6(
                        buf,
                        off,
                        WuadDnsServer.answer(
                            dns,
                            ipv6(buf, 8),
                            DnsCodec.u16(buf, off),
                            ipv6(buf, 24),
                            53
                        )
                    )
                }
            }
            6 -> tcp.onSegment(buf, true, off)
        }
    }

    private fun writeUdp4(orig: ByteArray, ihl: Int, dns: ByteArray) {
        val udpLen = 8 + dns.size
        val total = ihl + udpLen
        val out = ByteArray(total)
        System.arraycopy(orig, 0, out, 0, ihl + 8)
        System.arraycopy(dns, 0, out, ihl + 8, dns.size)
        System.arraycopy(orig, 12, out, 16, 4)
        System.arraycopy(orig, 16, out, 12, 4)
        out[ihl] = orig[ihl + 2]
        out[ihl + 1] = orig[ihl + 3]
        out[ihl + 2] = orig[ihl]
        out[ihl + 3] = orig[ihl + 1]
        DnsCodec.putU16(out, 2, total)
        DnsCodec.putU16(out, 10, 0)
        val ipCks = DnsCodec.ipv4Checksum(out.copyOfRange(0, ihl))
        DnsCodec.putU16(out, 10, ipCks.toInt() and 0xffff)
        DnsCodec.putU16(out, ihl + 4, udpLen)
        DnsCodec.putU16(out, ihl + 6, 0)
        val src = out.copyOfRange(12, 16)
        val dst = out.copyOfRange(16, 20)
        val udp = out.copyOfRange(ihl, total)
        val udpCks = DnsCodec.udpChecksum(src, dst, udp)
        DnsCodec.putU16(out, ihl + 6, udpCks.toInt() and 0xffff)
        writeRaw(out)
    }

    private fun writeUdp6(orig: ByteArray, udpOff: Int, dns: ByteArray) {
        val udpLen = 8 + dns.size
        val out = ByteArray(udpOff + udpLen)
        System.arraycopy(orig, 0, out, 0, udpOff + 8)
        System.arraycopy(dns, 0, out, udpOff + 8, dns.size)
        System.arraycopy(orig, 8, out, 24, 16)
        System.arraycopy(orig, 24, out, 8, 16)
        out[6] = 17
        out[7] = 64
        DnsCodec.putU16(out, 4, udpLen)
        out[udpOff] = orig[udpOff + 2]
        out[udpOff + 1] = orig[udpOff + 3]
        out[udpOff + 2] = orig[udpOff]
        out[udpOff + 3] = orig[udpOff + 1]
        DnsCodec.putU16(out, udpOff + 4, udpLen)
        DnsCodec.putU16(out, udpOff + 6, 0)
        val src = out.copyOfRange(8, 24)
        val dst = out.copyOfRange(24, 40)
        val udp = out.copyOfRange(udpOff, out.size)
        val csum = DnsCodec.ipv6TransportChecksum(src, dst, 17, udp)
        DnsCodec.putU16(out, udpOff + 6, csum.toInt() and 0xffff)
        writeRaw(out)
    }

    private fun ipv4(buf: ByteArray, off: Int): String =
        (0..3).joinToString(".") { ((buf[off + it].toInt()) and 0xff).toString() }

    private fun ipv6(buf: ByteArray, off: Int): String = try {
        java.net.InetAddress.getByAddress(buf.copyOfRange(off, off + 16)).hostAddress.orEmpty()
    } catch (_: Exception) {
        ""
    }

    private fun writeRaw(out: ByteArray) {
        synchronized(outLock) {
            if (dead.get()) return
            try {
                output.write(out)
            } catch (_: Exception) {
            }
        }
    }
}
