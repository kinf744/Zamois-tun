package com.kighmu.vpn.engines

import com.kighmu.vpn.models.XrayConfig
import android.content.Context
import android.os.ParcelFileDescriptor
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.utils.KighmuLogger
import com.trilead.ssh2.Connection
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import java.io.*
import java.net.*
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.VpnService
import java.security.SecureRandom
import java.util.concurrent.LinkedBlockingQueue
import javax.net.ssl.*

// ─────────────────────────────────────────────────────────────────────────────
// Mode 4: SSH SSL/TLS Engine
// ─────────────────────────────────────────────────────────────────────────────

class SshSslEngine(
    private val config: KighmuConfig,
    private val context: Context
) : TunnelEngine {

    companion object {
        const val TAG = "SshSslEngine"
        fun getFreePort(): Int = try { java.net.ServerSocket(0).use { it.localPort } } catch (_: Exception) { 10804 }
    }

    private var _socksPort: Int = 0
    fun getSocksPort(): Int = _socksPort
    private val LOCAL_SOCKS_PORT: Int get() {
        if (_socksPort == 0) _socksPort = Companion.getFreePort()
        return _socksPort
    }
    private var running = false
    private var sshConnection: Connection? = null
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val receiveQueue = LinkedBlockingQueue<ByteArray>(1000)

    private val sslConfig get() = config.sshSsl
    private val sshConfig get() = config.sshCredentials

    override suspend fun start(): Int {
        running = true
        KighmuLogger.info(TAG, "Démarrage tunnel SSH SSL/TLS")

        withContext(Dispatchers.IO) {
            try {
                // SSH via SSL socket - utiliser ProxyData pour passer par le tunnel TLS
                val sslSocket = buildSslSocket()
        KighmuLogger.info(TAG, "SSL handshake OK ✅")
                // Créer un ServerSocket local qui forward vers le SSL socket
                val localPort = findFreeLocalPort()
                val bridge = java.net.ServerSocket(localPort)
                bridge.soTimeout = 10000 // Timeout court pour l'acceptation
                
                Thread {
                    try {
                        val client = bridge.accept()
                        bridge.close()
                        client.tcpNoDelay = true
                        
                        val t1 = Thread {
                            try {
                                val i = client.getInputStream()
                                val o = sslSocket.outputStream
                                val buf = ByteArray(16384)
                                var n: Int
                                while (running && !client.isClosed && !sslSocket.isClosed) {
                                    n = i.read(buf)
                                    if (n <= 0) break
                                    o.write(buf, 0, n)
                                    o.flush()
                                }
                            } catch (_: Exception) {} finally {
                                try { client.close() } catch (_: Exception) {}
                                try { sslSocket.close() } catch (_: Exception) {}
                            }
                        }
                        
                        val t2 = Thread {
                            try {
                                val i = sslSocket.inputStream
                                val o = client.getOutputStream()
                                val buf = ByteArray(16384)
                                var n: Int
                                while (running && !client.isClosed && !sslSocket.isClosed) {
                                    n = i.read(buf)
                                    if (n <= 0) break
                                    o.write(buf, 0, n)
                                    o.flush()
                                }
                            } catch (_: Exception) {} finally {
                                try { client.close() } catch (_: Exception) {}
                                try { sslSocket.close() } catch (_: Exception) {}
                            }
                        }
                        
                        t1.isDaemon = true
                        t2.isDaemon = true
                        t1.start()
                        t2.start()
                    } catch (e: Exception) {
                        KighmuLogger.error(TAG, "Bridge error: ${e.message}")
                        try { bridge.close() } catch (_: Exception) {}
                    }
                }.start()
                // Bridge banniere : lit SSH-2.0-xxx puis relaie tout a Trilead
                val bannerProxyPort = run {
                    val ss = java.net.ServerSocket(0); ss.reuseAddress = true; val p = ss.localPort; ss.close(); p
                }
                val bannerLatch = java.util.concurrent.CountDownLatch(1)
                var capturedBanner = ""
                Thread {
                    try {
                        val proxyServer = java.net.ServerSocket(bannerProxyPort)
                        bannerLatch.countDown()
                        val trileadSock = proxyServer.accept()
                        proxyServer.close()
                        val realSock = java.net.Socket("127.0.0.1", localPort)
                        realSock.soTimeout = 5000
                        val realIn = realSock.getInputStream()
                        val bannerBytes = StringBuilder()
                        var b: Int
                        while (realIn.read().also { b = it } != -1) {
                            bannerBytes.append(b.toChar())
                            if (bannerBytes.endsWith("\n")) break
                        }
                        capturedBanner = bannerBytes.toString().trim()
                        val trileadOut = trileadSock.getOutputStream()
                        trileadOut.write(bannerBytes.toString().toByteArray())
                        trileadOut.flush()
                        val t1 = Thread { try { realIn.copyTo(trileadSock.getOutputStream()) } catch (_: Exception) {} }
                        val t2 = Thread { try { trileadSock.getInputStream().copyTo(realSock.getOutputStream()) } catch (_: Exception) {} }
                        t1.isDaemon = true; t2.isDaemon = true
                        t1.start(); t2.start()
                    } catch (e: Exception) {
                        bannerLatch.countDown()
                        KighmuLogger.error(TAG, "BannerProxy error: ${e.message}")
                    }
                }.also { it.isDaemon = true }.start()
                bannerLatch.await(3, java.util.concurrent.TimeUnit.SECONDS)
                val conn = Connection("127.0.0.1", bannerProxyPort)
                conn.connect(null, 30000, 30000)
                val authenticated = conn.authenticateWithPassword(sslConfig.sshUser, sslConfig.sshPass)
                if (!authenticated) throw Exception("SSH auth echoue pour ${sslConfig.sshUser}")
                conn.createDynamicPortForwarder(LOCAL_SOCKS_PORT)
                sshConnection = conn
                KighmuLogger.info(TAG, "SSH SSL/TLS connecté ✅")
            } catch (e: Exception) {
                KighmuLogger.error(TAG, "SSH SSL/TLS erreur: ${e.message}")
                throw e
            }
        }

        return LOCAL_SOCKS_PORT
    }

    private fun findFreeLocalPort(): Int = java.net.ServerSocket(0).use { it.localPort }

    private fun buildSslSocket(): SSLSocket {
        val tm = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
            override fun getAcceptedIssuers() = arrayOf<java.security.cert.X509Certificate>()
        })
        val tlsVer = if (sslConfig.tlsVersion.isBlank()) "TLS" else sslConfig.tlsVersion
        val sslContext = SSLContext.getInstance(tlsVer).also { it.init(null, tm, SecureRandom()) }

        val factory = sslContext.socketFactory
        val host = if (sslConfig.sshHost.isNotBlank()) sslConfig.sshHost else sshConfig.host
        val port = if (sslConfig.sshPort > 0) sslConfig.sshPort else 443
        val socket = factory.createSocket(host, port) as SSLSocket

        if (sslConfig.sni.isNotEmpty()) {
            val params = SSLParameters()
            params.serverNames = listOf(SNIHostName(sslConfig.sni))
            socket.sslParameters = params
        }

        socket.startHandshake()
        return socket
    }


    override fun startTun2Socks(fd: Int) {
        try {
            KighmuLogger.info(TAG, "Démarrage HevTun2Socks (SSH SSL/TLS)")
            HevTun2Socks.init()
            if (HevTun2Socks.isAvailable) {
                // On récupère le VpnService depuis le contexte si possible, ou on utilise le relay Kotlin en fallback
                val vpnService = context as? android.net.VpnService
                if (vpnService != null) {
                    HevTun2Socks.start(context, fd, LOCAL_SOCKS_PORT, vpnService, mtu = 8500)
                    KighmuLogger.info(TAG, "HevTun2Socks démarré avec succès ✅")
                } else {
                    KighmuLogger.error(TAG, "Contexte n'est pas un VpnService, fallback impossible")
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
        try { HevTun2Socks.stop() } catch (_: Exception) {}
        try { sshConnection?.close() } catch (_: Exception) {}
        engineScope.cancel()
    }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = receiveQueue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS)
    override fun isRunning() = running
}

// ─────────────────────────────────────────────────────────────────────────────
// Mode 5: Xray Engine
// ─────────────────────────────────────────────────────────────────────────────

class XrayEngine(
    private val config: KighmuConfig,
    private val context: Context,
    var dnsttProxyPort: Int = 0,  // Si > 0, Xray route via dnstt sur ce port
    private val instanceId: Int = 0,
    private val vpnService: VpnService? = null
) : TunnelEngine {
    private var _socksPort: Int = 0
    fun getSocksPort(): Int = _socksPort
    private val LOCAL_SOCKS_PORT: Int get() {
        if (_socksPort == 0) _socksPort = Companion.getFreePort()
        return _socksPort
    }

    companion object {
        const val TAG = "XrayEngine"
        const val LOCAL_HTTP_PORT = 10809
        // Port dynamique choisi par l'OS
        fun getFreePort(): Int = try { java.net.ServerSocket(0).use { it.localPort } } catch (_: Exception) { 10808 }
    }

    private var running = false
    private var xrayProcess: Process? = null
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val receiveQueue = LinkedBlockingQueue<ByteArray>(1000)

    override suspend fun start(): Int {
        running = true
        KighmuLogger.info(TAG, "Démarrage Xray...")

        withContext(Dispatchers.IO) {
            val xrayConfigFile = writeXrayConfig()
            val xrayBinary = extractXrayBinary()
            if (xrayBinary != null) {
                try {
                    startXrayProcess(xrayBinary, xrayConfigFile)
                    // Attendre que Xray soit prêt sur le port
                    var ready = false
                    repeat(20) {
                        if (!ready) {
                            delay(200)
                            try {
                                val s = java.net.Socket()
                                s.connect(java.net.InetSocketAddress("127.0.0.1", LOCAL_SOCKS_PORT), 200)
                                s.close()
                                ready = true
                            } catch (_: Exception) {}
                        }
                    }
                    if (!ready) throw Exception("Xray n'a pas demarre dans les temps")
                } catch (e: Exception) {
                    KighmuLogger.error(TAG, "Erreur démarrage Xray: ${e.message}")
                    throw e
                }
            } else {
                throw Exception("Xray binary introuvable - verifiez libxray.so dans jniLibs")
            }
        }

        return LOCAL_SOCKS_PORT
    }

    private fun writeXrayConfig(): File {
        val xrayConfig = config.xray
        // Choisir la bonne config selon le mode tunnel
        val isV2Dns = config.tunnelMode == com.kighmu.vpn.models.TunnelMode.V2RAY_SLOWDNS
        var jsonConfig = when {
            isV2Dns && config.v2dns.jsonConfig.isNotBlank() -> config.v2dns.jsonConfig
            isV2Dns && xrayConfig.v2dnsJsonConfig.isNotBlank() -> xrayConfig.v2dnsJsonConfig
            else -> {
                val active = XrayConfig.migrate(xrayConfig).getActiveJson()
                if (active.isNotBlank() && active != XrayConfig.defaultXrayConfig) active
                else buildXrayConfigFromFields(xrayConfig)
            }
        }
        // Normaliser le port SOCKS et nettoyer geoip/geosite via JSON parsing
        try {
            val obj = org.json.JSONObject(jsonConfig)
            // Nettoyer et normaliser les inbounds
            val inbounds = obj.optJSONArray("inbounds")
            var hasSocks = false
            val cleanedInbounds = org.json.JSONArray()
            if (inbounds != null) {
                for (i in 0 until inbounds.length()) {
                    val inbound = inbounds.getJSONObject(i)
                    val proto = inbound.optString("protocol")
                    val listenAddr = inbound.optString("listen", "127.0.0.1")
                    val inPort = inbound.optString("port", "0").toIntOrNull() ?: 0
                    // dokodemo-door : forcer listen 127.0.0.1 et port string->int
                    if (proto == "dokodemo-door") {
                        inbound.put("listen", "127.0.0.1")
                        if (inPort > 0) inbound.put("port", inPort)
                    }
                    if (listenAddr == "0.0.0.0") {
                        inbound.put("listen", "127.0.0.1")
                    }
                    if (proto == "socks") {
                        // Accepter port SOCKS dans la plage 10800-10810, sinon forcer LOCAL_SOCKS_PORT
                        if (inPort in 10800..10810) {
                            _socksPort = inPort
                            inbound.put("listen", "127.0.0.1")
                        } else {
                            inbound.put("port", LOCAL_SOCKS_PORT)
                            inbound.put("listen", "127.0.0.1")
                        }
                        hasSocks = true
                    }
                    cleanedInbounds.put(inbound)
                }
                obj.put("inbounds", cleanedInbounds)
                if (!hasSocks) {
                    val socksInbound = org.json.JSONObject()
                    socksInbound.put("listen", "127.0.0.1")
                    socksInbound.put("port", LOCAL_SOCKS_PORT)
                    socksInbound.put("protocol", "socks")
                    socksInbound.put("settings", org.json.JSONObject().put("udp", true))
                    cleanedInbounds.put(socksInbound)
                    obj.put("inbounds", cleanedInbounds)
                }
            }
            // Xray 26.x: supprimer allowInsecure de tous les tlsSettings
            val outbounds = obj.optJSONArray("outbounds")
            if (outbounds != null) {
                for (i in 0 until outbounds.length()) {
                    val ob = outbounds.getJSONObject(i)
                    val ss = ob.optJSONObject("streamSettings")
                    val tls = ss?.optJSONObject("tlsSettings")
                    if (tls != null) {
                        tls.remove("allowInsecure")
                        ss.put("tlsSettings", tls)
                        ob.put("streamSettings", ss)
                    }
                }
                obj.put("outbounds", outbounds)
            }
            jsonConfig = obj.toString()
            val routing = obj.optJSONObject("routing")
            if (routing != null) {
                val rules = routing.optJSONArray("rules")
                if (rules != null) {
                    val cleaned = org.json.JSONArray()
                    for (i in 0 until rules.length()) {
                        val rule = rules.getJSONObject(i)
                        val ip = rule.optJSONArray("ip")?.toString() ?: ""
                        val domain = rule.optJSONArray("domain")?.toString() ?: ""
                        if (!ip.contains("geoip:") && !domain.contains("geosite:")) {
                            cleaned.put(rule)
                        }
                    }
                    routing.put("rules", cleaned)
                    obj.put("routing", routing)
                    jsonConfig = obj.toString(2)
                }
            }
            // Mode V2RAY_SLOWDNS: remplacer address/port par 127.0.0.1:dnsttPort
            if (dnsttProxyPort > 0) {
                val outbounds = obj.optJSONArray("outbounds")
                if (outbounds != null) {
                    for (i in 0 until outbounds.length()) {
                        val ob = outbounds.getJSONObject(i)
                        val proto = ob.optString("protocol")
                        val tag = ob.optString("tag", "")
                        if (proto != "freedom" && proto != "blackhole" && proto != "socks" && tag != "direct") {
                            val settings = ob.optJSONObject("settings")
                            val vnext = settings?.optJSONArray("vnext")
                            if (vnext != null && vnext.length() > 0) {
                                val server = vnext.getJSONObject(0)
                                server.put("address", "127.0.0.1")
                                server.put("port", dnsttProxyPort)
                            }
                        }
                    }
                    obj.put("outbounds", outbounds)
                    jsonConfig = obj.toString()
                    KighmuLogger.info(TAG, "Config Xray+dnstt finalisée")
                }
            }
        } catch (e: Exception) {
            KighmuLogger.error(TAG, "Erreur configuration Xray: ${e.message}")
        }
        val fileName = if (instanceId == 0) "xray_config.json" else "xray_config_$instanceId.json"
        val file = File(context.filesDir, fileName)
        file.writeText(jsonConfig)
        return file
    }
    private fun buildXrayConfigFromFields(xc: com.kighmu.vpn.models.XrayConfig): String {
        // Optimisation StreamSettings: Mux activé pour la stabilité du flux streaming
        val kcpHeader = if (xc.wsPath.isEmpty()) "none" else xc.wsPath
        val tlsSec = if (xc.tls) "tls" else "none"
        val tlsBlock = if (xc.tls) """, "tlsSettings": { "serverName": "${xc.sni}", "fingerprint": "chrome" }""".trimIndent() else ""
        val streamSettings = when (xc.transport) {
            "ws", "websocket" -> """
                "streamSettings": {
                    "network": "ws",
                    "security": "$tlsSec"$tlsBlock,
                    "wsSettings": { "path": "${xc.wsPath}", "headers": { "Host": "${xc.wsHost}" } }
                }
            """.trimIndent()
            "grpc" -> """
                "streamSettings": {
                    "network": "grpc",
                    "security": "$tlsSec"$tlsBlock,
                    "grpcSettings": { "serviceName": "${xc.wsPath}" }
                }
            """.trimIndent()
            "h2", "http" -> """
                "streamSettings": {
                    "network": "h2",
                    "security": "$tlsSec"$tlsBlock,
                    "httpSettings": { "path": "${xc.wsPath}", "host": ["${xc.wsHost}"] }
                }
            """.trimIndent()
            "xhttp" -> """
                "streamSettings": {
                    "network": "xhttp",
                    "security": "$tlsSec"$tlsBlock,
                    "xhttpSettings": {
                        "path": "${xc.wsPath}",
                        "host": "${xc.wsHost}",
                        "mode": "stream-up",
                        "scMaxConcurrentPosts": 16,
                        "scMinPostsIntervalMs": 10,
                        "scMaxEachPostBytes": 1000000,
                        "noSSEHeader": true,
                        "xPaddingBytes": "100-1000"
                    }
                }
            """.trimIndent()
            "splithttp" -> """
                "streamSettings": {
                    "network": "splithttp",
                    "security": "$tlsSec"$tlsBlock,
                    "splithttpSettings": {
                        "path": "${xc.wsPath}",
                        "host": "${xc.wsHost}",
                        "mode": "stream-up",
                        "scMaxConcurrentPosts": 16,
                        "scMinPostsIntervalMs": 10,
                        "scMaxEachPostBytes": 1000000
                    }
                }
            """.trimIndent()
            "kcp", "mkcp" -> """
                "streamSettings": {
                    "network": "kcp",
                    "security": "$tlsSec"$tlsBlock,
                    "kcpSettings": {
                        "mtu": 1350, "tti": 20,
                        "uplinkCapacity": 5, "downlinkCapacity": 20,
                        "congestion": false, "readBufferSize": 2, "writeBufferSize": 2,
                        "header": { "type": "$kcpHeader" },
                        "seed": "${xc.wsHost}"
                    }
                }
            """.trimIndent()
            "httpupgrade" -> """
                "streamSettings": {
                    "network": "httpupgrade",
                    "security": "$tlsSec"$tlsBlock,
                    "httpupgradeSettings": { "path": "${xc.wsPath}", "host": "${xc.wsHost}" }
                }
            """.trimIndent()
            "tcp", "raw" -> """
                "streamSettings": {
                    "network": "tcp",
                    "security": "$tlsSec"$tlsBlock,
                    "tcpSettings": { "header": { "type": "none" } }
                }
            """.trimIndent()
            else -> """ "streamSettings": { \"network\": \"tcp\" } """
        }

        val outbound = when (xc.protocol) {
            "vmess" -> """{ "protocol": "vmess", "settings": { "vnext": [{ "address": "${xc.serverAddress}", "port": ${xc.serverPort}, "users": [{ "id": "${xc.uuid}", "alterId": 0, "security": "${xc.encryption}" }] }] }, $streamSettings, "mux": { "enabled": false } }"""
            "vless" -> """{ "protocol": "vless", "settings": { "vnext": [{ "address": "${xc.serverAddress}", "port": ${xc.serverPort}, "users": [{ "id": "${xc.uuid}", "encryption": "none" }] }] }, $streamSettings, "mux": { "enabled": false } }"""
            "trojan" -> """{ "protocol": "trojan", "settings": { "servers": [{ "address": "${xc.serverAddress}", "port": ${xc.serverPort}, "password": "${xc.uuid}" }] }, $streamSettings, "mux": { "enabled": false } }"""
            else -> """{ "protocol": "${xc.protocol}", "settings": {} }"""
        }

        // Optimisation Inbound: Sniffing activé pour un meilleur routage DNS/HTTP
        val inbounds = """
            [
                { 
                    "port": $LOCAL_SOCKS_PORT, 
                    "protocol": "socks", 
                    "settings": { "udp": true, "auth": "noauth" },
                    "sniffing": { "enabled": false }
                },
                { 
                    "port": $LOCAL_HTTP_PORT, 
                    "protocol": "http",
                    "sniffing": { "enabled": false }
                }
            ]
        """.trimIndent()

        return """{ 
            "log": { "loglevel": "warning" }, 
            "inbounds": $inbounds, 
            "outbounds": [$outbound, { "protocol": "freedom", "tag": "direct" }], 
            "routing": { "domainStrategy": "AsIs", "rules": [] } 
        }"""
    }

    private fun extractXrayBinary(): File? {
        // Chercher libxray.so dans nativeLibraryDir (jniLibs)
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val libxray = File(nativeDir, "libxray.so")
        if (libxray.exists()) {
            libxray.setExecutable(true)
            return libxray
        }
        // Fallback assets
        val abi = android.os.Build.SUPPORTED_ABIS[0]
        val target = File(context.filesDir, "xray")
        return try {
            context.assets.open("xray/$abi/xray").use { inp ->
                target.outputStream().use { out -> inp.copyTo(out) }
            }
            target.setExecutable(true)
            target
        } catch (e: Exception) {
            KighmuLogger.error(TAG, "Xray introuvable - vérifiez l installation")
            null
        }
    }

    private fun startXrayProcess(binary: File, configFile: File) {
        val cmd = arrayOf(binary.absolutePath, "run", "-c", configFile.absolutePath)
        xrayProcess = Runtime.getRuntime().exec(cmd)
        val proc = xrayProcess!!
        // Thread stderr+stdout Xray - filtré et reformaté
        fun processXrayLine(line: String) {
            if (line.isBlank() || line.length > 500) return
            val lineLower = line.lowercase()
            when {
                // Xray démarré avec succès
                lineLower.contains("started") && lineLower.contains("xray") -> {
                    val ver = Regex("""Xray ([\d.]+)""").find(line)?.groupValues?.get(1) ?: ""
                    KighmuLogger.info(TAG, "Xray démarré" + if (ver.isNotEmpty()) " v$ver ✅" else " ✅")
                }
                // Erreurs critiques seulement (ignorer warnings/deprecations)
                (lineLower.contains("error") || lineLower.contains("fatal"))
                && !lineLower.contains("warning")
                && !lineLower.contains("deprecated")
                && !lineLower.contains("common/errors")
                && !lineLower.contains("will be removed")
                && !lineLower.contains("please")
                && !lineLower.contains("read/write on closed pipe")
                && !lineLower.contains("io: read")
                && !lineLower.contains("failed to dial")
                && !lineLower.contains("connection reset")
                && !lineLower.contains("broken pipe")
                && !lineLower.contains("use of closed")
                && !lineLower.contains("EOF")
                && !lineLower.contains("websocket")
                && !lineLower.contains("transport/internet")
                && !lineLower.contains("dial to")
                && !lineLower.contains("read/ write")
                && !lineLower.contains("closed pipe")
                && !lineLower.contains("proxy/")
                && !lineLower.contains("app/proxyman") ->
                    KighmuLogger.error(TAG, "Xray erreur: ${line.take(150)}")
                // Ignorer : warnings dépréciations, timestamps, INFO verbeux
            }
        }
        Thread {
            try { proc.errorStream.bufferedReader().forEachLine { processXrayLine(it) } }
            catch (_: Exception) {}
        }.start()
        Thread {
            try { proc.inputStream.bufferedReader().forEachLine { processXrayLine(it) } }
            catch (_: Exception) {}
        }.start()
    }

    private fun startFallbackProxy() {
        engineScope.launch {
            val server = ServerSocket(LOCAL_SOCKS_PORT)
            while (running) {
                try {
                    val client = server.accept()
                    launch { handleDirectSocks(client) }
                } catch (e: Exception) { if (running) KighmuLogger.error(TAG, e.message ?: "") }
            }
            server.close()
        }
    }

    private suspend fun handleDirectSocks(client: Socket) = withContext(Dispatchers.IO) {
        try {
            val inp = DataInputStream(client.getInputStream())
            val out = DataOutputStream(client.getOutputStream())
            if (inp.read() != 5) { client.close(); return@withContext }
            val nm = inp.read(); inp.skipBytes(nm)
            out.write(byteArrayOf(5, 0)); out.flush()
            inp.read(); inp.read(); inp.read()
            val atyp = inp.read()
            val host = when (atyp) {
                1 -> { val b = ByteArray(4); inp.readFully(b); InetAddress.getByAddress(b).hostAddress!! }
                3 -> { val l = inp.read(); val b = ByteArray(l); inp.readFully(b); String(b) }
                4 -> { val b = ByteArray(16); inp.readFully(b); InetAddress.getByAddress(b).hostAddress!! }
                else -> { client.close(); return@withContext }
            }
            val port = (inp.read() shl 8) or inp.read()
            out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush()
            val server = Socket(host, port)
            val buf = ByteArray(8192)
            val j1 = launch { try { val i = client.getInputStream(); val o = server.getOutputStream(); var l: Int; while (i.read(buf).also { l = it } > 0) { o.write(buf, 0, l); o.flush() } } catch (_: Exception) {} }
            val j2 = launch { try { val i = server.getInputStream(); val o = client.getOutputStream(); var l: Int; while (i.read(buf).also { l = it } > 0) { o.write(buf, 0, l); o.flush() } } catch (_: Exception) {} }
            j1.join(); j2.cancel()
            server.close()
        } catch (_: Exception) { } finally { client.close() }
    }


    fun startTun2SocksOnPort(fd: Int, port: Int) {
        startTun2SocksInternal(fd, port)
    }

    override fun startTun2Socks(fd: Int) {
        startTun2SocksInternal(fd, LOCAL_SOCKS_PORT)
    }

    private fun startTun2SocksInternal(fd: Int, targetPort: Int) {
        engineScope.launch(Dispatchers.IO) {
            try {
                // 1. Priorité 1 : HevTun2Socks (UDP natif, MTU 8500, pas de udpgw)
                com.kighmu.vpn.engines.HevTun2Socks.init()
                if (com.kighmu.vpn.engines.HevTun2Socks.isAvailable) {
                    KighmuLogger.info(TAG, "Utilisation de HevTun2Socks")
                    val t = Thread {
                        try {
                            vpnService?.let {
                                // MTU 1400 optimal pour TCP/mobile (évite fragmentation 4G/LTE)
                                com.kighmu.vpn.engines.HevTun2Socks.start(context, fd, targetPort, it, 1400)
                            }
                            KighmuLogger.info(TAG, "HevTun2Socks démarré ✅")
                        } catch (e: Exception) {
                            KighmuLogger.error(TAG, "Erreur HevTun2Socks: ${e.message}")
                        }
                    }
                    t.isDaemon = true
                    t.start()
                    return@launch
                }

                // 2. Fallback : Tun2Socks JNI (UDP limité, requiert udpgw:7300)
                if (com.kighmu.vpn.engines.Tun2Socks.isAvailable) {
                    KighmuLogger.warning(TAG, "HevTun2Socks indisponible - fallback Tun2Socks JNI")
                    val t = Thread {
                        val result = com.kighmu.vpn.engines.Tun2Socks.runTun2Socks(
                            fd, 1500, "10.0.0.2", "255.255.255.0",
                            "127.0.0.1:$targetPort", "127.0.0.1:7300",
                            false, 3
                        )
                    }
                    t.isDaemon = true
                    t.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, _ -> }
                    t.start()
                    return@launch
                }

                // 3. Dernier recours : Relay Kotlin
                KighmuLogger.warning(TAG, "Aucun moteur JNI disponible - Relay Kotlin")
                val pfd = android.os.ParcelFileDescriptor.fromFd(fd)
                val relay = com.kighmu.vpn.vpn.Tun2SocksRelay(pfd.fileDescriptor, "127.0.0.1", targetPort)
                relay.start()
            } catch (e: Exception) {
                KighmuLogger.error(TAG, "Erreur critique startTun2Socks: ${e.message}")
            }
        }
    }

    override suspend fun stop() {
        running = false
        KighmuLogger.info(TAG, "Arrêt forcé de Xray...")
        
        try {
            xrayProcess?.let { p ->
                p.inputStream?.close()
                p.errorStream?.close()
                p.outputStream?.close()
                p.destroyForcibly()
            }
        } catch (_: Exception) {}
        
        xrayProcess = null
        _socksPort = 0
        dnsttProxyPort = 0
        
        // Arrêter HevTun2Socks proprement avant le prochain démarrage
        try { com.kighmu.vpn.engines.HevTun2Socks.stop() } catch (_: Exception) {}

        // Commande de secours pour libérer le port SOCKS si nécessaire
        try {
            Runtime.getRuntime().exec(arrayOf("sh", "-c", "fuser -k 10808/tcp")).waitFor()
        } catch (_: Exception) {}

        engineScope.cancel()
    }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = receiveQueue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS)
    override fun isRunning() = running
}

// ─────────────────────────────────────────────────────────────────────────────
// Mode 6: Xray + SlowDNS
// ─────────────────────────────────────────────────────────────────────────────

class XraySlowDnsEngine(
    private val config: KighmuConfig,
    private val context: Context
) : TunnelEngine {

    private val dnsttEngine = SlowDnsEngine(config, context)
    private val xray = XrayEngine(config, context)
    private val TAG = "XraySlowDns"

    override suspend fun start(): Int {
        KighmuLogger.info(TAG, "Démarrage dnstt (sans SSH)...")
        val dnsttPort = dnsttEngine.startDnsttOnly()
        KighmuLogger.info(TAG, "dnstt prêt sur port $dnsttPort - démarrage Xray")
        xray.dnsttProxyPort = dnsttPort
        return xray.start()
    }

    override fun startTun2Socks(fd: Int) = xray.startTun2Socks(fd)
    override suspend fun stop() { xray.dnsttProxyPort = 0; xray.stop(); dnsttEngine.stop() }
    override suspend fun sendData(data: ByteArray, length: Int) = xray.sendData(data, length)
    override suspend fun receiveData(): ByteArray? = xray.receiveData()
    override fun isRunning() = xray.isRunning()
}

// ─────────────────────────────────────────────────────────────────────────────
// Mode 7: Hysteria Engine
// ─────────────────────────────────────────────────────────────────────────────
