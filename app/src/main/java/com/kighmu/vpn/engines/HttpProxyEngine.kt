package com.kighmu.vpn.engines

import android.content.Context
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.utils.KighmuLogger
import com.trilead.ssh2.Connection
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

class HttpProxyEngine(
    private val config: KighmuConfig,
    private val context: Context
) : TunnelEngine {

    companion object {
        const val TAG = "HttpProxyEngine"
        fun getFreePort(): Int = try {
            java.net.ServerSocket(0).use { it.localPort }
        } catch (_: Exception) { 10801 }
        const val CRLF = "\r\n"
        const val PIPE_BUFFER_SIZE = 131072
        const val MTU = 8500
    }

    private var _socksPort: Int = 0
    private val LOCAL_SOCKS_PORT: Int get() {
        if (_socksPort == 0) _socksPort = getFreePort()
        return _socksPort
    }

    private var running = false
    private var sshConnection: Connection? = null
    private var proxySocket: Socket? = null
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val proxy get() = config.httpProxy
    private val ssh get() = object {
        val host get() = config.httpProxy.sshHost
        val port get() = config.httpProxy.sshPort
        val username get() = config.httpProxy.sshUser
        val password get() = config.httpProxy.sshPass
    }

    override suspend fun start(): Int = withContext(Dispatchers.IO) {
        running = true
                        
        if (proxy.proxyHost.isBlank()) throw Exception("Proxy Host manquant")
        if (ssh.host.isBlank()) throw Exception("SSH Host manquant")
        if (ssh.username.isBlank()) throw Exception("SSH Username manquant")

                val sock = Socket()
        sock.setPerformancePreferences(0, 0, 1)
        sock.receiveBufferSize = PIPE_BUFFER_SIZE
        sock.sendBufferSize    = PIPE_BUFFER_SIZE
        sock.keepAlive  = true
        sock.tcpNoDelay = true
        sock.soTimeout  = 0
        // Protéger le socket contre le tunnel VPN
        try { (context as? android.net.VpnService)?.protect(sock) } catch (_: Exception) {}
        sock.connect(InetSocketAddress(proxy.proxyHost, proxy.proxyPort), 15000)
        proxySocket = sock
        
        val out: OutputStream = sock.getOutputStream()
        val inp: InputStream  = sock.getInputStream()

        val rawPayload = if (proxy.customPayload.isNotBlank()) proxy.customPayload
        else "CONNECT [host]:[port] HTTP/1.1[crlf]Host: [host]:[port][crlf]Proxy-Connection: Keep-Alive[crlf][crlf]"

        val payload = rawPayload
            .replace("[host]",       ssh.host)
            .replace("[HOST]",       ssh.host)
            .replace("[real_host]",  ssh.host)
            .replace("[REAL_HOST]",  ssh.host)
            .replace("[port]",       ssh.port.toString())
            .replace("[PORT]",       ssh.port.toString())
            .replace("[proxy_host]", proxy.proxyHost)
            .replace("[proxy_port]", proxy.proxyPort.toString())
            .replace("[crlf]",       CRLF)
            .replace("[CRLF]",       CRLF)
            .replace("[cr]",         "\r")
            .replace("[lf]",         "\n")
            .replace("\\r\\n",       CRLF)
            .replace("\\r",          "\r")
            .replace("\\n",          "\n")

                sendPayload(out, payload, rawPayload)
        
                val isConnect = rawPayload.trimStart().startsWith("CONNECT", ignoreCase = true)
        val firstLine = readHttpLine(inp)
        KighmuLogger.info(TAG, "Response: $firstLine")

        val isError = firstLine.contains("400") || firstLine.contains("403") ||
                firstLine.contains("407") || firstLine.contains("502") ||
                firstLine.contains("404") || firstLine.contains("500")

        if (isConnect && !firstLine.contains("200") && !firstLine.contains("101")) {
            consumeHeaders(inp)
            throw Exception("Proxy CONNECT refuse: $firstLine")
        }
        if (isError) {
            consumeHeaders(inp)
            throw Exception("Proxy erreur: $firstLine")
        }
        consumeHeaders(inp)
        
                val bridgeSS = java.net.ServerSocket(0)
        val bridgePort = bridgeSS.localPort
        
        val sshVersionLatch = java.util.concurrent.CountDownLatch(1)
        var capturedSshVersion = ""
        Thread {
            try {
                val trileadSock = bridgeSS.accept()
                bridgeSS.close()
                trileadSock.receiveBufferSize = PIPE_BUFFER_SIZE
                trileadSock.sendBufferSize    = PIPE_BUFFER_SIZE
                trileadSock.tcpNoDelay = true
                val realIn = sock.getInputStream()
                val versionBytes = StringBuilder()
                var b: Int
                while (realIn.read().also { b = it } != -1) {
                    versionBytes.append(b.toChar())
                    if (versionBytes.endsWith("\n")) break
                }
                capturedSshVersion = versionBytes.toString().trim()
                val trileadOut = trileadSock.getOutputStream()
                trileadOut.write(versionBytes.toString().toByteArray())
                trileadOut.flush()
                sshVersionLatch.countDown()
                val t1 = Thread {
                    try { pipe(realIn, trileadSock.getOutputStream()) } catch (_: Exception) {}
                }
                val t2 = Thread {
                    try { pipe(trileadSock.getInputStream(), sock.getOutputStream()) } catch (_: Exception) {}
                }
                t1.isDaemon = true; t2.isDaemon = true
                t1.start(); t2.start()
            } catch (_: Exception) { sshVersionLatch.countDown() }
        }.also { it.isDaemon = true }.start()

        sshVersionLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        if (capturedSshVersion.isNotEmpty()) KighmuLogger.info(TAG, capturedSshVersion)
        val conn = Connection("127.0.0.1", bridgePort)
        conn.connect(null, 30000, 30000)

        val authenticated = conn.authenticateWithPassword(ssh.username, ssh.password)
        if (!authenticated) throw Exception("SSH auth echoue pour ${ssh.username}")
        KighmuLogger.info(TAG, "Auth complete")
        // ── Lire banner SSH via reflection (AuthenticationManager.banner) ────
        try {
            val amField = conn.javaClass.getDeclaredField("am")
            amField.isAccessible = true
            val am = amField.get(conn)
            if (am != null) {
                val bannerField = am.javaClass.getDeclaredField("banner")
                bannerField.isAccessible = true
                val banner = bannerField.get(am) as? String
                if (!banner.isNullOrBlank()) {
                    banner.lines()
                        .map { it.trimEnd() }
                        .filter { it.isNotEmpty() }
                        .forEach { line -> KighmuLogger.info(TAG, line) }
                }
            }
        } catch (_: Exception) {}



        conn.createDynamicPortForwarder(LOCAL_SOCKS_PORT)
        
        sshConnection = conn
                LOCAL_SOCKS_PORT
    }

    private fun sendPayload(out: OutputStream, payload: String, raw: String) {
        when {
            raw.contains("[split]", ignoreCase = true) -> {
                val parts = payload.split("[split]", ignoreCase = true)
                parts.forEachIndexed { idx, part ->
                    out.write(part.toByteArray(Charsets.ISO_8859_1))
                    out.flush()
                    if (idx < parts.size - 1) {
                                                Thread.sleep(30)
                    }
                }
            }
            raw.contains("[delay]", ignoreCase = true) -> {
                val lines = payload.split(CRLF)
                lines.forEachIndexed { idx, line ->
                    val data = if (idx < lines.size - 1) "$line$CRLF" else line
                    out.write(data.toByteArray(Charsets.ISO_8859_1))
                    out.flush()
                    Thread.sleep(20)
                }
            }
            else -> {
                out.write(payload.toByteArray(Charsets.ISO_8859_1))
                out.flush()
            }
        }
    }

    private fun consumeHeaders(inp: InputStream) {
        var h: String
        do {
            h = readHttpLine(inp)
            if (h.isNotEmpty()) {
                val hLower = h.lowercase()
                val skip = hLower.startsWith("report-to") || hLower.startsWith("nel:") ||
                        hLower.startsWith("cf-") || hLower.startsWith("alt-svc") ||
                        hLower.startsWith("cf-cache") || hLower.startsWith("date:") ||
                        hLower.startsWith("sec-websocket-accept")
                            }
        } while (h.isNotEmpty())
    }

    private fun readHttpLine(inp: InputStream): String {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val b = inp.read()
            if (b == -1) break
            if (prev == '\r'.code && b == '\n'.code) {
                if (sb.isNotEmpty()) sb.deleteCharAt(sb.length - 1)
                break
            }
            if (b == '\n'.code) break
            sb.append(b.toChar())
            prev = b
        }
        return sb.toString()
    }

    override fun startTun2Socks(fd: Int) {
        try {
                        HevTun2Socks.init()
            if (HevTun2Socks.isAvailable) {
                val vpnService = context as? android.net.VpnService
                if (vpnService != null) {
                    HevTun2Socks.start(context, fd, LOCAL_SOCKS_PORT, vpnService, mtu = MTU)
                                    } else {
                    KighmuLogger.error(TAG, "Contexte n'est pas un VpnService")
                }
            } else {
                KighmuLogger.error(TAG, "HevTun2Socks non disponible")
            }
        } catch (e: Exception) {
            KighmuLogger.error(TAG, "Erreur HevTun2Socks: ${e.message}")
        }
    }

    override suspend fun stop() {
        running = false
        try { HevTun2Socks.stop() }   catch (_: Exception) {}
        try { sshConnection?.close() } catch (_: Exception) {}
        try { proxySocket?.close() }   catch (_: Exception) {}
        engineScope.cancel()
            }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = null
    override fun isRunning() = running && sshConnection?.isAuthenticationComplete == true

    private fun pipe(inp: InputStream, out: OutputStream) {
        val buf = ByteArray(PIPE_BUFFER_SIZE)
        try {
            while (true) {
                val n = inp.read(buf)
                if (n == -1) break
                out.write(buf, 0, n)
                if (inp.available() <= 0) out.flush()
            }
        } catch (_: Exception) {}
    }
}
