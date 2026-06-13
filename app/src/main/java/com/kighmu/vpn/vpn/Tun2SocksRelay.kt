package com.kighmu.vpn.vpn

import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.*
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

class Tun2SocksRelay(
    private val tunFd: FileDescriptor,
    private val socksHost: String = "127.0.0.1",
    private val socksPort: Int = 10800
) {
    companion object { const val TAG = "Tun2SocksRelay" }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val sessions = ConcurrentHashMap<String, Session>()
    private val tunOut = FileOutputStream(tunFd)

    fun start() {
        scope.launch { readLoop() }
        KighmuLogger.info(TAG, "Relay demarre -> SOCKS5 $socksHost:$socksPort")
    }

    fun stop() {
        scope.cancel()
        sessions.values.forEach { it.close() }
        sessions.clear()
    }

    private suspend fun readLoop() = withContext(Dispatchers.IO) {
        val inp = FileInputStream(tunFd)
        // Buffer optimisé pour le débit streaming
        val buf = ByteArray(131072) // 128KB
        while (isActive) {
            try {
                val len = inp.read(buf)
                if (len < 0) break
                if (len < 20) continue
                
                // Traitement immédiat pour réduire la latence
                handlePacket(buf.copyOf(len))
            } catch (e: java.io.InterruptedIOException) {
                break // VPN deconnecte normalement
            } catch (e: Exception) {
                if (isActive) KighmuLogger.error(TAG, "readLoop: ${e.message}")
                break
            }
        }
        KighmuLogger.info(TAG, "readLoop termine")
    }

    private suspend fun handlePacket(pkt: ByteArray) {
        val ver = (pkt[0].toInt() and 0xFF) shr 4
        if (ver != 4) return
        val ihl = (pkt[0].toInt() and 0x0F) * 4
        val proto = pkt[9].toInt() and 0xFF
        val srcIp = ipStr(pkt, 12)
        val dstIp = ipStr(pkt, 16)

        when (proto) {
            6 -> handleTcp(pkt, ihl, srcIp, dstIp)
            17 -> handleUdp(pkt, ihl, srcIp, dstIp)
        }
    }

    private suspend fun handleTcp(pkt: ByteArray, ihl: Int, srcIp: String, dstIp: String) {
        if (pkt.size < ihl + 20) return
        val srcPort = port(pkt, ihl)
        val dstPort = port(pkt, ihl + 2)
        val flags = pkt[ihl + 13].toInt() and 0xFF
        val syn = flags and 0x02 != 0
        val fin = flags and 0x01 != 0
        val rst = flags and 0x04 != 0
        val dataOff = ihl + ((pkt[ihl + 12].toInt() and 0xF0) shr 4) * 4
        val key = "$srcIp:$srcPort-$dstIp:$dstPort"

        if (syn && !sessions.containsKey(key)) {
            val session = Session(key, srcIp, srcPort, dstIp, dstPort)
            sessions[key] = session
            scope.launch {
                session.connect(socksHost, socksPort)
                session.startReading { data -> writeIpPacket(dstIp, srcIp, dstPort, srcPort, data) }
            }
        }

        if (fin || rst) {
            sessions.remove(key)?.close()
            return
        }

        if (dataOff < pkt.size) {
            val data = pkt.copyOfRange(dataOff, pkt.size)
            if (data.isNotEmpty()) sessions[key]?.write(data)
        }
    }

    private suspend fun handleUdp(pkt: ByteArray, ihl: Int, @Suppress("UNUSED_PARAMETER") srcIp: String, @Suppress("UNUSED_PARAMETER") dstIp: String) {
        if (pkt.size < ihl + 8) return
        val dstPort = port(pkt, ihl + 2)
        val dataOff = ihl + 8
        if (dataOff >= pkt.size) return
        val data = pkt.copyOfRange(dataOff, pkt.size)

        // DNS uniquement
        if (dstPort != 53) return

        scope.launch(Dispatchers.IO) {
            try {
                val sock = connectSocks5("8.8.8.8", 53) ?: return@launch
                // DNS over TCP: prefixer avec longueur 2 bytes
                val out = sock.getOutputStream()
                out.write(byteArrayOf((data.size shr 8).toByte(), (data.size and 0xFF).toByte()))
                out.write(data)
                out.flush()
                val lenBuf = ByteArray(2)
                val inp = sock.getInputStream()
                inp.read(lenBuf)
                val len = ((lenBuf[0].toInt() and 0xFF) shl 8) or (lenBuf[1].toInt() and 0xFF)
                val resp = ByteArray(len)
                var r = 0; while (r < len) r += inp.read(resp, r, len - r)
                sock.close()
                KighmuLogger.info(TAG, "DNS reponse ${resp.size} bytes")
            } catch (e: Exception) {
                KighmuLogger.error(TAG, "UDP/DNS: ${e.message}")
            }
        }
    }

    private fun writeIpPacket(srcIp: String, dstIp: String, srcPort: Int, dstPort: Int, data: ByteArray) {
        try {
            // Construire paquet IP+TCP minimal avec PSH+ACK
            val ipHeader = ByteArray(20)
            val tcpHeader = ByteArray(20)
            val total = 20 + 20 + data.size

            // IP header
            ipHeader[0] = 0x45.toByte()
            ipHeader[1] = 0
            ipHeader[2] = (total shr 8).toByte()
            ipHeader[3] = (total and 0xFF).toByte()
            ipHeader[8] = 64 // TTL
            ipHeader[9] = 6  // TCP
            val srcParts = srcIp.split(".").map { it.toInt() }
            val dstParts = dstIp.split(".").map { it.toInt() }
            for (i in 0..3) {
                ipHeader[12 + i] = srcParts[i].toByte()
                ipHeader[16 + i] = dstParts[i].toByte()
            }
            ipHeader[10] = 0; ipHeader[11] = 0
            val ipCsum = checksum(ipHeader)
            ipHeader[10] = (ipCsum shr 8).toByte()
            ipHeader[11] = (ipCsum and 0xFF).toByte()

            // TCP header
            tcpHeader[0] = (srcPort shr 8).toByte()
            tcpHeader[1] = (srcPort and 0xFF).toByte()
            tcpHeader[2] = (dstPort shr 8).toByte()
            tcpHeader[3] = (dstPort and 0xFF).toByte()
            tcpHeader[12] = 0x50.toByte() // data offset = 5
            tcpHeader[13] = 0x18.toByte() // PSH + ACK

            val pkt = ipHeader + tcpHeader + data
            synchronized(tunOut) { tunOut.write(pkt) }
        } catch (e: Exception) {
            KighmuLogger.error(TAG, "writeIpPacket: ${e.message}")
        }
    }

    fun connectSocks5(host: String, port: Int): Socket? {
        return try {
            val sock = Socket()
            sock.soTimeout = 10000
            sock.connect(InetSocketAddress(socksHost, socksPort), 5000)
            val out = sock.getOutputStream()
            val inp = sock.getInputStream()
            out.write(byteArrayOf(0x05, 0x01, 0x00)); out.flush()
            inp.read(); inp.read()
            val hostBytes = host.toByteArray()
            out.write(byteArrayOf(0x05, 0x01, 0x00, 0x03, hostBytes.size.toByte()) +
                hostBytes + byteArrayOf((port shr 8).toByte(), (port and 0xFF).toByte()))
            out.flush()
            val resp = ByteArray(256); var total = 0
            while (total < 4) total += inp.read(resp, total, 4 - total)
            if (resp[1] != 0x00.toByte()) { sock.close(); return null }
            val extra = when (resp[3].toInt()) { 1 -> 6; 3 -> inp.read() + 3; 4 -> 18; else -> 2 }
            val tmp = ByteArray(extra); var r = 0
            while (r < extra) r += inp.read(tmp, r, extra - r)
            sock
        } catch (e: Exception) { null }
    }

    private fun ipStr(pkt: ByteArray, off: Int) =
        "${pkt[off].toInt() and 0xFF}.${pkt[off+1].toInt() and 0xFF}.${pkt[off+2].toInt() and 0xFF}.${pkt[off+3].toInt() and 0xFF}"

    private fun port(pkt: ByteArray, off: Int) =
        ((pkt[off].toInt() and 0xFF) shl 8) or (pkt[off+1].toInt() and 0xFF)

    private fun checksum(buf: ByteArray): Int {
        var sum = 0
        for (i in 0 until buf.size - 1 step 2)
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i+1].toInt() and 0xFF)
        if (buf.size % 2 != 0) sum += (buf.last().toInt() and 0xFF) shl 8
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }

    inner class Session(
        val key: String,
        val srcIp: String, val srcPort: Int,
        val dstIp: String, val dstPort: Int
    ) {
        private var socket: Socket? = null
        private val writeScope = CoroutineScope(Dispatchers.IO)

        suspend fun connect(@Suppress("UNUSED_PARAMETER") socksHost: String, @Suppress("UNUSED_PARAMETER") socksPort: Int) {
            socket = connectSocks5(dstIp, dstPort)
            if (socket == null) {
                sessions.remove(key)
                KighmuLogger.error(TAG, "Connexion echouee: $dstIp:$dstPort")
            }
        }

        suspend fun startReading(onData: (ByteArray) -> Unit) = withContext(Dispatchers.IO) {
            val sock = socket ?: return@withContext
            // Buffer de lecture agrandi pour le streaming
            val buf = ByteArray(65536) // 64KB
            try {
                while (true) {
                    val n = sock.getInputStream().read(buf)
                    if (n <= 0) break
                    onData(buf.copyOf(n))
                }
            } catch (e: Exception) {
                // connexion fermee
            } finally {
                sessions.remove(key)
                close()
            }
        }

        fun write(data: ByteArray) {
            try { socket?.getOutputStream()?.write(data); socket?.getOutputStream()?.flush() }
            catch (e: Exception) { sessions.remove(key); close() }
        }

        fun close() {
            writeScope.cancel()
            try { socket?.close() } catch (_: Exception) {}
        }
    }
}
